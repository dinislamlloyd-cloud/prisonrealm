package com.prisonrealm.prison;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.prisonrealm.PrisonRealmMod;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.particle.DustParticleEffect;
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
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Вся логика ареста, запечатывания кубом, срока, освобождения и побега. */
public final class PrisonManager {
	/** Срок по наручникам. */
	public static final int DEFAULT_SECONDS = 300;
	/** Срок по запечатывающему кубу. */
	public static final int SEAL_SECONDS = 600;
	/** Длительность «запечатывания»: 4 секунды. */
	public static final int SEAL_WINDUP_TICKS = 80;

	private static final DustParticleEffect TENTACLE_DUST =
			new DustParticleEffect(new Vector3f(0.30f, 0.05f, 0.40f), 1.6f);

	/** Начальное состояние куба: маленький, дальше он «раскрывается» анимацией. */
	private static final String CUBE_NBT = "{item:{id:\"prisonrealm:sealing_cube\",Count:1b},"
			+ "item_display:\"fixed\",billboard:\"center\",Tags:[\"prisonrealm_cube\"],"
			+ "brightness:{sky:15,block:15},"
			+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
			+ "translation:[0f,0f,0f],scale:[0.3f,0.3f,0.3f]}}";

	private static final class Seal {
		int age = 0;
		UUID cubeId;
		Vec3d cubePos;
	}

	/** Игроки, которых сейчас запечатывает куб. */
	private static final Map<UUID, Seal> PENDING = new HashMap<>();

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

	// ------------------------------------------------------ запечатывание

	/** Начинает «запечатывание»: появляется куб, тянет щупальца и затягивает цель. */
	public static boolean beginSeal(ServerPlayerEntity target, ServerPlayerEntity owner) {
		if (PENDING.containsKey(target.getUuid()) || isPrisoner(target)) {
			return false;
		}
		ServerWorld w = target.getServerWorld();

		// Куб парит перед целью, на линии «цель -> владелец куба».
		Vec3d eye = target.getEyePos();
		Vec3d toOwner = owner.getEyePos().subtract(eye);
		Vec3d flat = new Vec3d(toOwner.x, 0, toOwner.z);
		if (flat.lengthSquared() < 1.0E-4) {
			flat = new Vec3d(0, 0, 1);
		}
		Vec3d cubePos = eye.add(flat.normalize().multiply(3.0)).add(0, 0.5, 0);

		Seal seal = new Seal();
		seal.cubePos = cubePos;
		seal.cubeId = spawnCube(w, cubePos);
		PENDING.put(target.getUuid(), seal);

		target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, SEAL_WINDUP_TICKS + 10, 10));
		w.playSound(null, BlockPos.ofFloored(cubePos), SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE,
				SoundCategory.HOSTILE, 1.5f, 0.5f);
		target.sendMessage(Text.translatable("prisonrealm.being_sealed"), true);
		return true;
	}

	private static UUID spawnCube(ServerWorld w, Vec3d pos) {
		try {
			Entity cube = EntityType.ITEM_DISPLAY.create(w);
			if (cube == null) {
				return null;
			}
			cube.readNbt(StringNbtReader.parse(CUBE_NBT));
			cube.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0.0f, 0.0f);
			w.spawnEntity(cube);
			return cube.getUuid();
		} catch (CommandSyntaxException ex) {
			PrisonRealmMod.LOGGER.warn("Could not create sealing cube display: {}", ex.getMessage());
			return null;
		}
	}

	/** Меняет часть данных уже существующего куба (например, размер с анимацией). */
	private static void patchCube(ServerWorld w, UUID id, String snbt) {
		if (id == null) {
			return;
		}
		Entity cube = w.getEntity(id);
		if (cube == null) {
			return;
		}
		try {
			NbtCompound patch = StringNbtReader.parse(snbt);
			NbtCompound merged = cube.writeNbt(new NbtCompound());
			for (String key : patch.getKeys()) {
				merged.put(key, patch.get(key).copy());
			}
			cube.readNbt(merged);
		} catch (CommandSyntaxException ex) {
			PrisonRealmMod.LOGGER.warn("Could not update sealing cube display: {}", ex.getMessage());
		}
	}

	private static String scaleNbt(float scale, int duration) {
		return "{transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
				+ "translation:[0f,0f,0f],scale:[" + scale + "f," + scale + "f," + scale + "f]},"
				+ "start_interpolation:0,interpolation_duration:" + duration + "}";
	}

	/** Один тик анимации: куб раскрывается, щупальца хватают цель, цель затягивается. */
	private static void stepSeal(ServerPlayerEntity p, Seal s) {
		ServerWorld w = p.getServerWorld();
		Vec3d cube = s.cubePos;
		Vec3d body = p.getPos().add(0, p.getHeight() * 0.6, 0);

		if (s.age == 1) {
			patchCube(w, s.cubeId, scaleNbt(3.0f, 30));
		}

		// Тёмный вихрь вокруг куба.
		if (s.age % 2 == 0) {
			double a = s.age * 0.35;
			w.spawnParticles(ParticleTypes.SQUID_INK,
					cube.x + Math.cos(a) * 1.6, cube.y + Math.sin(a * 0.7) * 0.8, cube.z + Math.sin(a) * 1.6,
					2, 0.05, 0.05, 0.05, 0.01);
			w.spawnParticles(ParticleTypes.SOUL, cube.x, cube.y, cube.z, 1, 0.8, 0.8, 0.8, 0.02);
		}

		// Щупальца: из куба к телу цели, извиваясь.
		if (s.age >= 20 && s.age % 2 == 0) {
			Vec3d dir = body.subtract(cube);
			if (dir.length() > 0.1) {
				Vec3d d = dir.normalize();
				Vec3d side = d.crossProduct(new Vec3d(0, 1, 0));
				if (side.lengthSquared() < 1.0E-4) {
					side = new Vec3d(1, 0, 0);
				}
				side = side.normalize();
				Vec3d up = side.crossProduct(d).normalize();
				double grow = Math.min(1.0, (s.age - 20) / 15.0);

				for (int k = 0; k < 6; k++) {
					double phase = k * 1.05 + s.age * 0.35;
					for (int i = 0; i <= 15; i++) {
						double t = i / 15.0;
						if (t > grow) {
							break;
						}
						double env = Math.sin(t * Math.PI) * 0.4;
						Vec3d pos = cube.add(dir.multiply(t))
								.add(side.multiply(Math.sin(t * 9 + phase) * env))
								.add(up.multiply(Math.cos(t * 9 + phase) * env));
						w.spawnParticles(TENTACLE_DUST, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
					}
				}
			}
		}

		// Затягивание цели в куб.
		if (s.age >= 40 && s.age % 2 == 0) {
			Vec3d pull = cube.subtract(body);
			if (pull.length() > 1.5) {
				p.setVelocity(pull.normalize().multiply(0.25));
				p.velocityModified = true;
				p.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(p));
			}
		}
	}

	private static void finishSeal(ServerPlayerEntity p, Seal s) {
		ServerWorld w = p.getServerWorld();
		Vec3d cube = s.cubePos;
		Vec3d body = p.getPos().add(0, p.getHeight() * 0.6, 0);

		w.spawnParticles(ParticleTypes.FLASH, cube.x, cube.y, cube.z, 1, 0, 0, 0, 0);
		w.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, body.x, body.y, body.z, 40, 0.4, 0.6, 0.4, 0.1);
		w.playSound(null, BlockPos.ofFloored(cube), SoundEvents.ENTITY_WARDEN_SONIC_BOOM,
				SoundCategory.HOSTILE, 2.0f, 0.6f);
		// Куб «схлопывается» и остаётся висеть на месте ареста, пока длится срок.
		patchCube(w, s.cubeId, scaleNbt(1.8f, 10));

		p.server.getPlayerManager().broadcast(Text.translatable("prisonrealm.sealed", p.getDisplayName()), false);
		if (!arrest(p, SEAL_SECONDS, s.cubeId)) {
			discardCube(w, s.cubeId);
		}
	}

	private static void discardCube(ServerWorld w, UUID id) {
		if (id == null) {
			return;
		}
		Entity cube = w.getEntity(id);
		if (cube != null) {
			cube.discard();
		}
	}

	// ---------------------------------------------------------------- арест

	public static boolean arrest(ServerPlayerEntity p, int seconds) {
		return arrest(p, seconds, null);
	}

	public static boolean arrest(ServerPlayerEntity p, int seconds, UUID cubeId) {
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
		e.cubeId = cubeId;
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

		RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, e.dimension);
		ServerWorld target = server.getWorld(key);
		breakCube(target, e.cubeId, escaped);

		p.changeGameMode(GameMode.byId(e.gameMode));
		p.getInventory().clear();
		p.getInventory().readNbt(e.inventory);
		p.playerScreenHandler.sendContentUpdates();
		p.clearStatusEffects();

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

	/** Куб на месте ареста рассыпается: при побеге с грохотом, при конце срока тихо. */
	private static void breakCube(ServerWorld w, UUID cubeId, boolean escaped) {
		if (w == null || cubeId == null) {
			return;
		}
		Entity cube = w.getEntity(cubeId);
		if (cube == null) {
			return;
		}
		Vec3d pos = cube.getPos();
		if (escaped) {
			w.spawnParticles(ParticleTypes.EXPLOSION, pos.x, pos.y, pos.z, 3, 0.8, 0.8, 0.8, 0.0);
			w.spawnParticles(ParticleTypes.SOUL, pos.x, pos.y, pos.z, 40, 0.8, 0.8, 0.8, 0.1);
			w.playSound(null, BlockPos.ofFloored(pos), SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.HOSTILE, 2.0f, 0.6f);
		} else {
			w.spawnParticles(ParticleTypes.POOF, pos.x, pos.y, pos.z, 20, 0.6, 0.6, 0.6, 0.02);
			w.playSound(null, BlockPos.ofFloored(pos), SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.HOSTILE, 1.5f, 0.6f);
		}
		cube.discard();
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
		Iterator<Map.Entry<UUID, Seal>> it = PENDING.entrySet().iterator();
		List<ServerPlayerEntity> ready = new ArrayList<>();
		List<Seal> readySeals = new ArrayList<>();
		while (it.hasNext()) {
			Map.Entry<UUID, Seal> entry = it.next();
			Seal seal = entry.getValue();
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(entry.getKey());
			if (p == null) {
				// Игрок вышел из игры во время запечатывания: куб убираем.
				for (ServerWorld world : server.getWorlds()) {
					discardCube(world, seal.cubeId);
				}
				it.remove();
				continue;
			}
			seal.age++;
			if (seal.age >= SEAL_WINDUP_TICKS) {
				it.remove();
				ready.add(p);
				readySeals.add(seal);
			} else {
				stepSeal(p, seal);
			}
		}
		for (int i = 0; i < ready.size(); i++) {
			finishSeal(ready.get(i), readySeals.get(i));
		}
	}
}
