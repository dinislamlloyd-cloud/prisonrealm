package com.prisonrealm.prison;

import com.prisonrealm.PrisonRealmMod;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtList;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Вся логика ареста, срока, освобождения и побега. */
public final class PrisonManager {
	/** Срок по наручникам. */
	public static final int DEFAULT_SECONDS = 300;
	/** Срок по запечатывающему кубу. */
	public static final int SEAL_SECONDS = 600;
	/** Сколько тиков цель стоит неподвижно перед запечатыванием. */
	public static final int SEAL_WINDUP_TICKS = 60;

	/** Игроки, которых сейчас запечатывает куб: uuid -> оставшиеся тики. */
	private static final Map<UUID, Integer> PENDING = new HashMap<>();

	private PrisonManager() {
	}

	public static boolean isPrisoner(PlayerEntity player) {
		if (!(player instanceof ServerPlayerEntity sp)) {
			return false;
		}
		return PrisonState.get(sp.server).has(sp.getUuid());
	}

	public static String formatTicks(int ticks) {
		int total = Math.max(0, ticks) / 20;
		return String.format("%d:%02d", total / 60, total % 60);
	}

	// ---------------------------------------------------------------- арест

	/** Начинает «запечатывание»: цель замирает, потом уходит в тюрьму. */
	public static boolean beginSeal(ServerPlayerEntity target) {
		if (PENDING.containsKey(target.getUuid()) || isPrisoner(target)) {
			return false;
		}
		PENDING.put(target.getUuid(), SEAL_WINDUP_TICKS);
		target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, SEAL_WINDUP_TICKS + 10, 10));
		target.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, SEAL_WINDUP_TICKS, 0));
		target.getServerWorld().playSound(null, target.getBlockPos(), SoundEvents.ENTITY_WARDEN_SONIC_CHARGE,
				SoundCategory.PLAYERS, 1.0f, 0.6f);
		target.sendMessage(Text.translatable("prisonrealm.being_sealed"), true);
		return true;
	}

	public static boolean arrest(ServerPlayerEntity p, int seconds) {
		MinecraftServer server = p.server;
		PrisonState state = PrisonState.get(server);
		ServerWorld prison = server.getWorld(PrisonRealmMod.PRISON_WORLD);
		if (prison == null || state.has(p.getUuid())) {
			return false;
		}

		PrisonState.Entry e = new PrisonState.Entry();
		e.ticksLeft = seconds * 20;
		e.dimension = p.getServerWorld().getRegistryKey().getValue();
		e.x = p.getX();
		e.y = p.getY();
		e.z = p.getZ();
		e.yaw = p.getYaw();
		e.pitch = p.getPitch();
		e.gameMode = p.interactionManager.getGameMode().getId();
		e.inventory = p.getInventory().writeNbt(new NbtList());
		state.put(p.getUuid(), e);

		PENDING.remove(p.getUuid());
		p.getInventory().clear();
		p.clearStatusEffects();
		p.changeGameMode(GameMode.ADVENTURE);

		PrisonBuilder.ensureBuilt(prison);
		toCell(p, prison);
		p.sendMessage(Text.translatable("prisonrealm.imprisoned", formatTicks(e.ticksLeft)), false);
		return true;
	}

	public static void toCell(ServerPlayerEntity p, ServerWorld prison) {
		int cx = PrisonBuilder.CELL_X[Math.floorMod(p.getUuid().hashCode(), PrisonBuilder.CELL_X.length)];
		p.teleport(prison, cx + 0.5, PrisonBuilder.Y + 1, PrisonBuilder.CELL_BACK_Z + 4.5, 0.0f, 0.0f);
		p.fallDistance = 0.0f;
	}

	// ---------------------------------------------------------- освобождение

	public static void release(ServerPlayerEntity p, boolean escaped) {
		MinecraftServer server = p.server;
		PrisonState state = PrisonState.get(server);
		PrisonState.Entry e = state.remove(p.getUuid());
		if (e == null) {
			return;
		}
		PENDING.remove(p.getUuid());

		p.changeGameMode(GameMode.byId(e.gameMode));
		p.getInventory().clear();
		p.getInventory().readNbt(e.inventory);
		p.playerScreenHandler.sendContentUpdates();
		p.clearStatusEffects();

		RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, e.dimension);
		ServerWorld target = server.getWorld(key);
		if (target == null) {
			target = server.getOverworld();
			BlockPos spawn = target.getSpawnPos();
			p.teleport(target, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0.0f, 0.0f);
		} else {
			p.teleport(target, e.x, e.y, e.z, e.yaw, e.pitch);
		}
		p.fallDistance = 0.0f;

		if (escaped) {
			// Беглец светится 3 минуты: его легко найти.
			p.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 20 * 180, 0));
			server.getPlayerManager().broadcast(Text.translatable("prisonrealm.escaped", p.getDisplayName()), false);
		} else {
			p.sendMessage(Text.translatable("prisonrealm.released"), false);
		}
	}

	public static void onRespawn(ServerPlayerEntity p) {
		PrisonState state = PrisonState.get(p.server);
		if (!state.has(p.getUuid())) {
			return;
		}
		ServerWorld prison = p.server.getWorld(PrisonRealmMod.PRISON_WORLD);
		if (prison != null) {
			p.changeGameMode(GameMode.ADVENTURE);
			toCell(p, prison);
		}
	}

	// ------------------------------------------------------------------ тик

	public static void tick(MinecraftServer server) {
		tickPending(server);
		if (server.getTicks() % 20 != 0) {
			return;
		}

		PrisonState state = PrisonState.get(server);
		if (state.isEmpty()) {
			return;
		}
		ServerWorld prison = server.getWorld(PrisonRealmMod.PRISON_WORLD);
		if (prison == null) {
			return;
		}

		for (ServerPlayerEntity p : new ArrayList<>(server.getPlayerManager().getPlayerList())) {
			PrisonState.Entry e = state.get(p.getUuid());
			if (e == null) {
				continue;
			}
			e.ticksLeft -= 20;
			state.markDirty();
			if (e.ticksLeft <= 0) {
				release(p, false);
				continue;
			}
			if (p.getServerWorld() != prison) {
				toCell(p, prison);
			}
			if (p.interactionManager.getGameMode() != GameMode.ADVENTURE) {
				p.changeGameMode(GameMode.ADVENTURE);
			}
			p.sendMessage(Text.translatable("prisonrealm.time_left", formatTicks(e.ticksLeft)), true);
		}
	}

	private static void tickPending(MinecraftServer server) {
		if (PENDING.isEmpty()) {
			return;
		}
		Iterator<Map.Entry<UUID, Integer>> it = PENDING.entrySet().iterator();
		java.util.List<ServerPlayerEntity> ready = new ArrayList<>();
		while (it.hasNext()) {
			Map.Entry<UUID, Integer> entry = it.next();
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(entry.getKey());
			if (p == null) {
				it.remove();
				continue;
			}
			int left = entry.getValue() - 1;
			if (left % 4 == 0) {
				p.getServerWorld().spawnParticles(ParticleTypes.SQUID_INK, p.getX(), p.getY() + 1.0, p.getZ(),
						12, 0.5, 0.8, 0.5, 0.02);
			}
			if (left <= 0) {
				it.remove();
				ready.add(p);
			} else {
				entry.setValue(left);
			}
		}
		for (ServerPlayerEntity p : ready) {
			arrest(p, SEAL_SECONDS);
		}
	}
}
