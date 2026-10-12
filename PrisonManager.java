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
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Вся логика ареста, запечатывания кубом, срока, освобождения и побега. */
public final class PrisonManager {
	/** Срок по наручникам. */
	public static final int DEFAULT_SECONDS = 300;
	/** Срок по запечатывающему кубу. */
	public static final int SEAL_SECONDS = 600;
	/** Длительность «запечатывания»: 4,8 секунды. */
	public static final int SEAL_WINDUP_TICKS = 96;

	// Расписание анимации запечатывания (в тиках от начала).
	private static final int ARMS_SPAWN_AT = 10;
	private static final int PANELS_SPAWN_AT = 36;
	private static final int PANELS_CLOSED_AT = 61;
	private static final int ARMS_RETRACT_AT = 56;
	private static final int ARMS_REMOVE_AT = 64;
	private static final int COMPRESS_AT = 64;

	private static final float PANEL_THICKNESS = 0.25f;
	private static final float PANEL_SIZE = 2.6f;

	/** Куб-«глаз», который парит перед целью и командует щупальцами. */
	private static final String CUBE_NBT = "{item:{id:\"prisonrealm:sealing_cube\",Count:1b},"
			+ "item_display:\"fixed\",billboard:\"center\",Tags:[\"prisonrealm_cube\"],"
			+ "brightness:{sky:15,block:15},"
			+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
			+ "translation:[0f,0f,0f],scale:[0.3f,0.3f,0.3f]}}";

	/** Готовый «запечатанный» куб, который остаётся на месте ареста. */
	private static final String SEALED_CUBE_NBT = "{block_state:{Name:\"minecraft:crying_obsidian\"},"
			+ "brightness:{sky:15,block:15},Tags:[\"prisonrealm_cube\"],"
			+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
			+ "translation:[-0.65f,-0.65f,-0.65f],scale:[1.3f,1.3f,1.3f]}}";

	private static final class Arm {
		UUID id;
		Quaternionf rotation;
		Vector3f translation;
		float length;
	}

	private static final class Seal {
		final ServerWorld world;
		int age = 0;
		UUID cubeId;
		Vec3d cubePos;
		Vec3d center;
		final List<Arm> arms = new ArrayList<>();
		final List<UUID> panels = new ArrayList<>();

		Seal(ServerWorld world) {
			this.world = world;
		}
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

	/** Начинает «запечатывание»: куб, руки, смыкающиеся грани и сжатие. */
	public static boolean beginSeal(ServerPlayerEntity target, ServerPlayerEntity owner) {
		if (PENDING.containsKey(target.getUuid()) || isPrisoner(target)) {
			return false;
		}
		ServerWorld w = target.getServerWorld();

		// Куб парит перед целью, на линии «цель -> владелец куба».
		Vec3d eye = target.getEyePos();
		// Если куб запускают на себя (команда /prison seal), он появляется там, куда смотрит цель.
		Vec3d toOwner = owner == target ? target.getRotationVec(1.0f) : owner.getEyePos().subtract(eye);
		Vec3d flat = new Vec3d(toOwner.x, 0, toOwner.z);
		if (flat.lengthSquared() < 1.0E-4) {
			flat = new Vec3d(0, 0, 1);
		}
		Vec3d cubePos = eye.add(flat.normalize().multiply(3.0)).add(0, 0.5, 0);

		Seal seal = new Seal(w);
		seal.cubePos = cubePos;
		seal.cubeId = spawnDisplay(w, EntityType.ITEM_DISPLAY, cubePos, CUBE_NBT);
		PENDING.put(target.getUuid(), seal);

		target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, SEAL_WINDUP_TICKS + 10, 10));
		sound(w, cubePos, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 1.5f, 0.5f);
		target.sendMessage(Text.translatable("prisonrealm.being_sealed"), true);
		return true;
	}

	// ---- вспомогательное: дисплей-сущности ----

	private static UUID spawnDisplay(ServerWorld w, EntityType<?> type, Vec3d pos, String snbt) {
		try {
			Entity e = type.create(w);
			if (e == null) {
				return null;
			}
			e.readNbt(StringNbtReader.parse(snbt));
			e.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0.0f, 0.0f);
			w.spawnEntity(e);
			return e.getUuid();
		} catch (CommandSyntaxException ex) {
			PrisonRealmMod.LOGGER.warn("Could not create display entity: {}", ex.getMessage());
			return null;
		}
	}

	/** Меняет часть данных уже существующей дисплей-сущности (например, размер с анимацией). */
	private static void patchDisplay(ServerWorld w, UUID id, String snbt) {
		if (id == null) {
			return;
		}
		Entity e = w.getEntity(id);
		if (e == null) {
			return;
		}
		try {
			NbtCompound patch = StringNbtReader.parse(snbt);
			NbtCompound merged = e.writeNbt(new NbtCompound());
			for (String key : patch.getKeys()) {
				merged.put(key, patch.get(key).copy());
			}
			e.readNbt(merged);
		} catch (CommandSyntaxException ex) {
			PrisonRealmMod.LOGGER.warn("Could not update display entity: {}", ex.getMessage());
		}
	}

	private static void discard(ServerWorld w, UUID id) {
		if (id == null) {
			return;
		}
		Entity e = w.getEntity(id);
		if (e != null) {
			e.discard();
		}
	}

	private static String f(float v) {
		return String.format(Locale.ROOT, "%.4ff", v);
	}

	private static String transformNbt(Vector3f translation, Quaternionf rotation, Vector3f scale) {
		return "transformation:{left_rotation:[" + f(rotation.x) + "," + f(rotation.y) + "," + f(rotation.z) + ","
				+ f(rotation.w) + "],right_rotation:[0f,0f,0f,1f],translation:[" + f(translation.x) + ","
				+ f(translation.y) + "," + f(translation.z) + "],scale:[" + f(scale.x) + "," + f(scale.y) + ","
				+ f(scale.z) + "]}";
	}

	private static String animNbt(String transform, int duration) {
		return "{" + transform + ",start_interpolation:0,interpolation_duration:" + duration + "}";
	}

	private static void sound(ServerWorld w, Vec3d pos, SoundEvent event, float volume, float pitch) {
		w.playSound(null, BlockPos.ofFloored(pos), event, SoundCategory.HOSTILE, volume, pitch);
	}

	private static String blockDisplayNbt(String transform) {
		return "{block_state:{Name:\"minecraft:crying_obsidian\"},brightness:{sky:15,block:15},"
				+ "Tags:[\"prisonrealm_cube\"]," + transform + "}";
	}

	// ---- руки ----

	private static final Vec3d[] ARM_TARGETS = {
			new Vec3d(0.0, 0.8, 0.0), new Vec3d(0.45, 0.4, 0.2), new Vec3d(-0.45, 0.4, -0.2),
			new Vec3d(0.3, -0.4, -0.4), new Vec3d(-0.3, -0.4, 0.4), new Vec3d(0.0, -0.8, 0.0)
	};

	private static String armTransform(Arm arm, float length) {
		return transformNbt(arm.translation, arm.rotation, new Vector3f(0.16f, 0.16f, length));
	}

	private static void spawnArms(Seal s, Vec3d body) {
		for (Vec3d off : ARM_TARGETS) {
			Vec3d dir = body.add(off).subtract(s.cubePos);
			double len = dir.length();
			if (len < 0.5) {
				continue;
			}
			Vector3f d = new Vector3f((float) (dir.x / len), (float) (dir.y / len), (float) (dir.z / len));
			Arm arm = new Arm();
			arm.length = (float) len;
			arm.rotation = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), d);
			// Нижний край сечения руки должен лежать на оси: сдвигаем на -R * (полширины, полширины, 0).
			Vector3f t = new Vector3f(0.08f, 0.08f, 0.0f);
			arm.rotation.transform(t);
			t.negate();
			arm.translation = t;
			arm.id = spawnDisplay(s.world, EntityType.BLOCK_DISPLAY, s.cubePos,
					blockDisplayNbt(armTransform(arm, 0.02f)));
			s.arms.add(arm);
		}
	}

	// ---- грани ----

	/** Грань куба: face 0..5 = +X, -X, +Y, -Y, +Z, -Z. */
	private static String panelTransform(int face, float distance, float size) {
		int axis = face / 2;
		float sign = face % 2 == 0 ? 1.0f : -1.0f;
		// Разный размер по осям, чтобы грани в углах не мерцали (z-fighting).
		float s = size * (1.0f + 0.04f * axis);
		Vector3f scale = new Vector3f(s, s, s);
		Vector3f center = new Vector3f(0, 0, 0);
		if (axis == 0) {
			scale.x = PANEL_THICKNESS;
			center.x = sign * distance;
		} else if (axis == 1) {
			scale.y = PANEL_THICKNESS;
			center.y = sign * distance;
		} else {
			scale.z = PANEL_THICKNESS;
			center.z = sign * distance;
		}
		Vector3f translation = new Vector3f(center.x - scale.x / 2, center.y - scale.y / 2, center.z - scale.z / 2);
		return transformNbt(translation, new Quaternionf(), scale);
	}

	private static void animatePanels(Seal s, float distance, float size, int duration) {
		for (int face = 0; face < s.panels.size(); face++) {
			patchDisplay(s.world, s.panels.get(face), animNbt(panelTransform(face, distance, size), duration));
		}
	}

	// ---- сама анимация ----

	private static void stepSeal(ServerPlayerEntity p, Seal s) {
		ServerWorld w = s.world;
		Vec3d cube = s.cubePos;
		Vec3d body = p.getPos().add(0, 0.9, 0);

		// 1. Куб раскрывается и «смотрит» на цель.
		if (s.age == 1) {
			patchDisplay(w, s.cubeId, animNbt(transformNbt(new Vector3f(), new Quaternionf(), new Vector3f(3.0f, 3.0f, 3.0f)), 24));
		}
		if (s.age % 2 == 0) {
			double a = s.age * 0.35;
			w.spawnParticles(ParticleTypes.SQUID_INK,
					cube.x + Math.cos(a) * 1.6, cube.y + Math.sin(a * 0.7) * 0.8, cube.z + Math.sin(a) * 1.6,
					2, 0.05, 0.05, 0.05, 0.01);
			w.spawnParticles(ParticleTypes.SOUL, cube.x, cube.y, cube.z, 1, 0.8, 0.8, 0.8, 0.02);
		}

		// 2. Из куба вытягиваются руки и хватают цель (игрока никуда не тянет).
		if (s.age == ARMS_SPAWN_AT) {
			spawnArms(s, body);
			sound(w, cube, SoundEvents.ENTITY_WARDEN_EMERGE, 1.5f, 0.7f);
		}
		if (s.age == ARMS_SPAWN_AT + 1) {
			for (Arm arm : s.arms) {
				patchDisplay(w, arm.id, animNbt(armTransform(arm, arm.length), 16));
			}
		}

		// 3. Шесть граней летят к игроку со всех сторон.
		if (s.age == PANELS_SPAWN_AT) {
			s.center = p.getPos().add(0, 0.9, 0);
			for (int face = 0; face < 6; face++) {
				s.panels.add(spawnDisplay(w, EntityType.BLOCK_DISPLAY, s.center,
						blockDisplayNbt(panelTransform(face, 3.6f, PANEL_SIZE))));
			}
			sound(w, s.center, SoundEvents.BLOCK_BEACON_DEACTIVATE, 2.0f, 0.5f);
		}
		if (s.age == PANELS_SPAWN_AT + 1) {
			animatePanels(s, 1.25f, PANEL_SIZE, PANELS_CLOSED_AT - PANELS_SPAWN_AT - 1);
		}

		// Руки убираются, когда грани почти сомкнулись.
		if (s.age == ARMS_RETRACT_AT) {
			for (Arm arm : s.arms) {
				patchDisplay(w, arm.id, animNbt(armTransform(arm, 0.02f), 6));
			}
		}
		if (s.age == ARMS_REMOVE_AT) {
			for (Arm arm : s.arms) {
				discard(w, arm.id);
			}
			s.arms.clear();
		}

		// 4. Куб сомкнулся: удар, затем сжатие вокруг игрока.
		if (s.age == PANELS_CLOSED_AT && s.center != null) {
			w.spawnParticles(ParticleTypes.EXPLOSION, s.center.x, s.center.y, s.center.z, 4, 0.8, 0.8, 0.8, 0.0);
			sound(w, s.center, SoundEvents.BLOCK_ANVIL_LAND, 2.0f, 0.5f);
		}
		if (s.age == COMPRESS_AT) {
			animatePanels(s, 0.62f, 1.5f, SEAL_WINDUP_TICKS - COMPRESS_AT - 4);
		}
		if (s.age > COMPRESS_AT && s.age % 8 == 0 && s.center != null) {
			sound(w, s.center, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 2.0f, 0.8f);
			w.spawnParticles(ParticleTypes.SQUID_INK, s.center.x, s.center.y, s.center.z, 8, 0.5, 0.5, 0.5, 0.02);
		}
	}

	private static void abortSeal(Seal s) {
		discard(s.world, s.cubeId);
		for (Arm arm : s.arms) {
			discard(s.world, arm.id);
		}
		for (UUID id : s.panels) {
			discard(s.world, id);
		}
	}

	private static void finishSeal(ServerPlayerEntity p, Seal s) {
		ServerWorld w = s.world;
		Vec3d center = s.center != null ? s.center : p.getPos().add(0, 0.9, 0);

		w.spawnParticles(ParticleTypes.FLASH, center.x, center.y, center.z, 1, 0, 0, 0, 0);
		w.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, center.x, center.y, center.z, 40, 0.4, 0.6, 0.4, 0.1);
		sound(w, center, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 2.0f, 0.6f);

		// Всё лишнее убираем, на месте остаётся один готовый куб.
		abortSeal(s);
		UUID sealed = spawnDisplay(w, EntityType.BLOCK_DISPLAY, center, SEALED_CUBE_NBT);

		p.server.getPlayerManager().broadcast(Text.translatable("prisonrealm.sealed", p.getDisplayName()), false);
		if (!arrest(p, SEAL_SECONDS, sealed)) {
			discard(w, sealed);
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
		Seal pending = PENDING.remove(p.getUuid());
		if (pending != null) {
			abortSeal(pending);
		}

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
			sound(w, pos, SoundEvents.BLOCK_GLASS_BREAK, 2.0f, 0.6f);
		} else {
			w.spawnParticles(ParticleTypes.POOF, pos.x, pos.y, pos.z, 20, 0.6, 0.6, 0.6, 0.02);
			sound(w, pos, SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, 1.5f, 0.6f);
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
			if (p == null || p.isDead()) {
				// Игрок вышел из игры или погиб во время запечатывания: убираем всю анимацию.
				abortSeal(seal);
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
