package com.prisonrealm.prison;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.prisonrealm.ModItems;
import com.prisonrealm.PrisonRealmMod;
import com.prisonrealm.item.SealingCubeItem;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.particle.DustParticleEffect;
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
import net.minecraft.util.Identifier;
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
import java.util.Set;
import java.util.UUID;

/**
 * Вся логика ареста, запечатывания кубом, срока, освобождения и побега.
 *
 * Запечатывание кубом:
 *  1. ПКМ кубом по игроку (или «close» в чат, глядя на игрока с кубом в руке):
 *     появляется куб-глаз, из него вытягиваются руки и держат цель,
 *     вокруг цели в воздухе зависают шесть красных кубов.
 *  2. Владелец пишет в чат «close»: кубы увеличиваются, летят на цель,
 *     смыкаются вокруг неё и сжимаются. Цель уходит в тюрьму.
 *  3. На месте остаётся голубой куб. Любой игрок в 2 блоках от него
 *     пишет «open», и заключённый выходит на свободу.
 */
public final class PrisonManager {
	/** Срок по наручникам. */
	public static final int DEFAULT_SECONDS = 300;
	/** Срок по запечатывающему кубу (если никто не скажет «open»). */
	public static final int SEAL_SECONDS = 600;

	// Фаза захвата (тики от начала).
	private static final int ARMS_SPAWN_AT = 10;
	private static final int FLOATS_SPAWN_AT = 16;
	private static final int READY_AT = 28;
	/** Сколько ждать «close», прежде чем печать рассеется: 20 секунд. */
	private static final int HOLD_TIMEOUT = 400;

	// Фаза закрытия (тики от момента «close»).
	private static final int C_GROW = 0;
	private static final int C_FLY = 10;
	private static final int C_ARMS_BACK = 14;
	private static final int C_IMPACT = 20;
	private static final int C_COMPRESS = 23;
	private static final int C_FINISH = 44;

	private static final float PANEL_THICKNESS = 0.25f;
	private static final float PANEL_SIZE = 2.6f;
	/** Насколько близко надо стоять к голубому кубу, чтобы сказать «open». */
	private static final double OPEN_RANGE = 2.0;
	private static final double LOOK_RANGE = 12.0;

	private static final Set<String> CLOSE_WORDS = Set.of("close", "закрыть", "закройся", "закрой");
	private static final Set<String> OPEN_WORDS = Set.of("open", "открыть", "откройся", "открой");

	private static final String FLESH_BLOCK = "minecraft:nether_wart_block";
	private static final String ARM_BLOCK = "minecraft:crimson_hyphae";
	private static final String SEALED_BLOCK = "minecraft:light_blue_concrete";
	/** Голубое свечение вокруг готового куба (0x55FFFF). */
	private static final int SEALED_GLOW = 0x55FFFF;
	private static final float SEALED_SIZE = 1.3f;

	private static final DustParticleEffect RED_DUST =
			new DustParticleEffect(new Vector3f(0.75f, 0.02f, 0.02f), 1.5f);
	private static final DustParticleEffect DARK_RED_DUST =
			new DustParticleEffect(new Vector3f(0.35f, 0.0f, 0.02f), 2.0f);
	private static final DustParticleEffect BLUE_DUST =
			new DustParticleEffect(new Vector3f(0.35f, 0.85f, 1.0f), 1.3f);

	/** Куб-«глаз», который парит перед целью и держит её руками. */
	private static final String EYE_NBT = "{item:{id:\"prisonrealm:sealing_cube\",Count:1b},"
			+ "item_display:\"fixed\",billboard:\"center\",Tags:[\"prisonrealm_cube\"],"
			+ "brightness:{sky:15,block:15},"
			+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
			+ "translation:[0f,0f,0f],scale:[0.3f,0.3f,0.3f]}}";

	private static final class Arm {
		UUID id;
		Quaternionf rotation;
		Vector3f translation;
		float length;
	}

	private static final class Seal {
		final ServerWorld world;
		final UUID owner;
		final Vec3d anchor;
		final Vec3d center;
		final Vec3d eyePos;
		int age = 0;
		UUID eyeId;
		final List<Arm> arms = new ArrayList<>();
		final List<UUID> floats = new ArrayList<>();
		boolean closeRequested;
		int closeAt = -1;

		Seal(ServerWorld world, UUID owner, Vec3d anchor, Vec3d eyePos) {
			this.world = world;
			this.owner = owner;
			this.anchor = anchor;
			this.center = anchor.add(0, 0.9, 0);
			this.eyePos = eyePos;
		}
	}

	/** Игроки, которых сейчас держит куб (ключ: UUID цели). */
	private static final Map<UUID, Seal> PENDING = new HashMap<>();

	private enum Reason { TIME, ESCAPE, OPENED }

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

	// ============================================================== чат

	/** Вызывается на каждое сообщение в чате. Реагирует на «close» и «open». */
	public static void onChat(ServerPlayerEntity sender, String raw) {
		String word = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[!.?,…]+$", "");
		if (CLOSE_WORDS.contains(word)) {
			sender.server.execute(() -> handleClose(sender));
		} else if (OPEN_WORDS.contains(word)) {
			sender.server.execute(() -> handleOpen(sender));
		}
	}

	private static void handleClose(ServerPlayerEntity sender) {
		if (isPrisoner(sender)) {
			return;
		}
		// Уже держим кого-то: закрываем печать.
		for (Seal s : PENDING.values()) {
			if (s.owner.equals(sender.getUuid()) && s.closeAt < 0) {
				s.closeRequested = true;
				return;
			}
		}
		// Иначе: с кубом в руке, глядя на игрока, запечатываем сразу.
		boolean holding = sender.getMainHandStack().isOf(ModItems.SEALING_CUBE)
				|| sender.getOffHandStack().isOf(ModItems.SEALING_CUBE);
		if (!holding) {
			return;
		}
		if (sender.getItemCooldownManager().isCoolingDown(ModItems.SEALING_CUBE)) {
			sender.sendMessage(Text.translatable("prisonrealm.cube_cooldown"), true);
			return;
		}
		ServerPlayerEntity target = lookTarget(sender);
		if (target == null) {
			sender.sendMessage(Text.translatable("prisonrealm.no_target"), true);
			return;
		}
		if (beginSeal(target, sender, true)) {
			sender.getItemCooldownManager().set(ModItems.SEALING_CUBE, SealingCubeItem.COOLDOWN_TICKS);
		}
	}

	private static void handleOpen(ServerPlayerEntity sender) {
		if (isPrisoner(sender)) {
			return;
		}
		MinecraftServer server = sender.server;
		PrisonState state = PrisonState.get(server);
		Identifier senderDim = sender.getServerWorld().getRegistryKey().getValue();
		for (Map.Entry<UUID, PrisonState.Entry> me : new ArrayList<>(state.all().entrySet())) {
			PrisonState.Entry e = me.getValue();
			if (e.cubeId == null || !e.dimension.equals(senderDim)) {
				continue;
			}
			Vec3d cube = new Vec3d(e.x, e.y + 0.9, e.z);
			if (sender.getPos().add(0, 0.9, 0).distanceTo(cube) > OPEN_RANGE + SEALED_SIZE / 2 + 0.3) {
				continue;
			}
			ServerPlayerEntity prisoner = server.getPlayerManager().getPlayer(me.getKey());
			Text name = prisoner != null ? prisoner.getDisplayName() : Text.translatable("prisonrealm.someone");
			server.getPlayerManager().broadcast(Text.translatable("prisonrealm.opened_by", sender.getDisplayName(), name), false);
			if (prisoner != null) {
				release(prisoner, Reason.OPENED);
			} else {
				// Заключённый не в сети: куб открываем сейчас, освободится при входе.
				breakCube(sender.getServerWorld(), e.cubeId, Reason.OPENED);
				e.cubeId = null;
				e.ticksLeft = 0;
				state.markDirty();
			}
			return;
		}
	}

	private static ServerPlayerEntity lookTarget(ServerPlayerEntity src) {
		Vec3d eye = src.getEyePos();
		Vec3d look = src.getRotationVec(1.0f);
		ServerPlayerEntity best = null;
		double bestDot = 0.97;
		for (ServerPlayerEntity o : src.getServerWorld().getPlayers()) {
			if (o == src || o.isSpectator() || !o.isAlive()) {
				continue;
			}
			Vec3d to = o.getPos().add(0, o.getHeight() * 0.5, 0).subtract(eye);
			double d = to.length();
			if (d > LOOK_RANGE || d < 0.5) {
				continue;
			}
			double dot = to.normalize().dotProduct(look);
			if (dot > bestDot && src.canSee(o)) {
				best = o;
				bestDot = dot;
			}
		}
		return best;
	}

	// ====================================================== запечатывание

	public static boolean beginSeal(ServerPlayerEntity target, ServerPlayerEntity owner) {
		return beginSeal(target, owner, false);
	}

	/** Куб хватает цель. Закрывается, когда владелец скажет «close» (или сразу, если closeNow). */
	public static boolean beginSeal(ServerPlayerEntity target, ServerPlayerEntity owner, boolean closeNow) {
		if (PENDING.containsKey(target.getUuid()) || isPrisoner(target)) {
			return false;
		}
		ServerWorld w = target.getServerWorld();

		// Куб-глаз парит перед целью, на линии «цель -> владелец куба».
		Vec3d eye = target.getEyePos();
		Vec3d toOwner = owner == target ? target.getRotationVec(1.0f) : owner.getEyePos().subtract(eye);
		Vec3d flat = new Vec3d(toOwner.x, 0, toOwner.z);
		if (flat.lengthSquared() < 1.0E-4) {
			flat = new Vec3d(0, 0, 1);
		}
		Vec3d eyePos = eye.add(flat.normalize().multiply(3.0)).add(0, 0.5, 0);

		Seal s = new Seal(w, owner.getUuid(), target.getPos(), eyePos);
		s.closeRequested = closeNow;
		s.eyeId = spawnDisplay(w, EntityType.ITEM_DISPLAY, eyePos, EYE_NBT);
		PENDING.put(target.getUuid(), s);

		target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, HOLD_TIMEOUT + 80, 10));
		sound(w, eyePos, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 1.5f, 0.5f);
		target.sendMessage(Text.translatable("prisonrealm.being_sealed"), true);
		if (!closeNow) {
			owner.sendMessage(Text.translatable("prisonrealm.say_close"), true);
		}
		return true;
	}

	// ---- дисплей-сущности ----

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

	/** Меняет часть данных существующей дисплей-сущности (например, размер с анимацией). */
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
		if (w == null || id == null) {
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

	private static String blockDisplayNbt(String block, String transform) {
		return "{block_state:{Name:\"" + block + "\"},brightness:{sky:15,block:15},"
				+ "Tags:[\"prisonrealm_cube\"]," + transform + "}";
	}

	/** Куб размера size с центром в offset (относительно точки сущности). */
	private static String cubeTransform(Vec3d offset, float size) {
		Vector3f t = new Vector3f((float) offset.x - size / 2, (float) offset.y - size / 2, (float) offset.z - size / 2);
		return transformNbt(t, new Quaternionf(), new Vector3f(size, size, size));
	}

	/** Куб размера size, повёрнутый на angle вокруг вертикали, с центром в точке сущности. */
	private static String spinTransform(float size, float angle) {
		Quaternionf rot = new Quaternionf().rotationY(angle);
		Vector3f t = new Vector3f(size / 2, size / 2, size / 2);
		rot.transform(t);
		t.negate();
		return transformNbt(t, rot, new Vector3f(size, size, size));
	}

	private static void sound(ServerWorld w, Vec3d pos, SoundEvent event, float volume, float pitch) {
		w.playSound(null, BlockPos.ofFloored(pos), event, SoundCategory.HOSTILE, volume, pitch);
	}

	// ---- руки ----

	private static final Vec3d[] ARM_TARGETS = {
			new Vec3d(0.0, 0.8, 0.0), new Vec3d(0.45, 0.4, 0.2), new Vec3d(-0.45, 0.4, -0.2),
			new Vec3d(0.3, -0.4, -0.4), new Vec3d(-0.3, -0.4, 0.4), new Vec3d(0.0, -0.8, 0.0)
	};

	private static String armTransform(Arm arm, float length) {
		return transformNbt(arm.translation, arm.rotation, new Vector3f(0.18f, 0.18f, length));
	}

	private static void spawnArms(Seal s) {
		for (Vec3d off : ARM_TARGETS) {
			Vec3d dir = s.center.add(off).subtract(s.eyePos);
			double len = dir.length();
			if (len < 0.5) {
				continue;
			}
			Vector3f d = new Vector3f((float) (dir.x / len), (float) (dir.y / len), (float) (dir.z / len));
			Arm arm = new Arm();
			arm.length = (float) len;
			arm.rotation = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), d);
			// Центр сечения руки должен лежать на оси: сдвигаем на -R * (полширины, полширины, 0).
			Vector3f t = new Vector3f(0.09f, 0.09f, 0.0f);
			arm.rotation.transform(t);
			t.negate();
			arm.translation = t;
			arm.id = spawnDisplay(s.world, EntityType.BLOCK_DISPLAY, s.eyePos,
					blockDisplayNbt(ARM_BLOCK, armTransform(arm, 0.02f)));
			s.arms.add(arm);
		}
	}

	private static void removeArms(Seal s) {
		for (Arm arm : s.arms) {
			discard(s.world, arm.id);
		}
		s.arms.clear();
	}

	// ---- кубы в воздухе ----

	/** Где висит i-й куб в ожидании «close» (относительно центра цели). */
	private static Vec3d floatOffset(int i, boolean up) {
		double ang = Math.toRadians(i * 60 + 30);
		double y = 1.5 + (i % 2) * 0.6 + (up ? 0.25 : 0.0);
		return new Vec3d(Math.cos(ang) * 3.0, y, Math.sin(ang) * 3.0);
	}

	/** Грань сомкнутого куба: face 0..5 = +X, -X, +Y, -Y, +Z, -Z. */
	private static String panelTransform(int face, float distance, float size) {
		int axis = face / 2;
		float sign = face % 2 == 0 ? 1.0f : -1.0f;
		// Разный размер по осям, чтобы грани в углах не мерцали.
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
		for (int face = 0; face < s.floats.size(); face++) {
			patchDisplay(s.world, s.floats.get(face), animNbt(panelTransform(face, distance, size), duration));
		}
	}

	// ---- шаг анимации ----

	private static void stepSeal(ServerPlayerEntity p, Seal s) {
		ServerWorld w = s.world;
		Vec3d eye = s.eyePos;
		Vec3d c = s.center;

		// Цель прикована к месту руками.
		if (p.getPos().squaredDistanceTo(s.anchor) > 0.09) {
			p.networkHandler.requestTeleport(s.anchor.x, s.anchor.y, s.anchor.z, p.getYaw(), p.getPitch());
		}

		// Красный вихрь вокруг куба-глаза.
		if (s.age % 2 == 0) {
			double a = s.age * 0.35;
			w.spawnParticles(RED_DUST,
					eye.x + Math.cos(a) * 1.6, eye.y + Math.sin(a * 0.7) * 0.8, eye.z + Math.sin(a) * 1.6,
					2, 0.05, 0.05, 0.05, 0.0);
			w.spawnParticles(ParticleTypes.CRIMSON_SPORE, eye.x, eye.y, eye.z, 2, 0.8, 0.8, 0.8, 0.0);
		}

		// ---------- захват ----------
		if (s.age == 1) {
			patchDisplay(w, s.eyeId, animNbt(transformNbt(new Vector3f(), new Quaternionf(), new Vector3f(3.0f, 3.0f, 3.0f)), 20));
		}
		if (s.age == ARMS_SPAWN_AT) {
			spawnArms(s);
			sound(w, eye, SoundEvents.ENTITY_WARDEN_EMERGE, 1.5f, 0.7f);
		}
		if (s.age == ARMS_SPAWN_AT + 1) {
			for (Arm arm : s.arms) {
				patchDisplay(w, arm.id, animNbt(armTransform(arm, arm.length), 14));
			}
		}
		if (s.age == FLOATS_SPAWN_AT) {
			for (int i = 0; i < 6; i++) {
				s.floats.add(spawnDisplay(w, EntityType.BLOCK_DISPLAY, c,
						blockDisplayNbt(FLESH_BLOCK, cubeTransform(floatOffset(i, false), 0.01f))));
			}
			sound(w, c, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.5f, 0.6f);
		}
		if (s.age == FLOATS_SPAWN_AT + 1) {
			for (int i = 0; i < s.floats.size(); i++) {
				patchDisplay(w, s.floats.get(i), animNbt(cubeTransform(floatOffset(i, false), 0.7f), 10));
			}
		}

		// Кубы покачиваются в воздухе, пока владелец не скажет «close».
		if (s.closeAt < 0 && s.age > READY_AT && s.age % 20 == 0) {
			boolean up = (s.age / 20) % 2 == 0;
			for (int i = 0; i < s.floats.size(); i++) {
				patchDisplay(w, s.floats.get(i), animNbt(cubeTransform(floatOffset(i, up), 0.7f), 20));
				Vec3d o = c.add(floatOffset(i, up));
				w.spawnParticles(DARK_RED_DUST, o.x, o.y, o.z, 3, 0.3, 0.3, 0.3, 0.0);
			}
		}
		if (s.age == READY_AT && !s.closeRequested) {
			ServerPlayerEntity owner = p.server.getPlayerManager().getPlayer(s.owner);
			if (owner != null) {
				owner.sendMessage(Text.translatable("prisonrealm.say_close"), true);
			}
		}

		if (s.closeAt < 0 && s.closeRequested && s.age >= READY_AT) {
			s.closeAt = s.age;
		}
		if (s.closeAt < 0) {
			return;
		}

		// ---------- закрытие ----------
		int t = s.age - s.closeAt;
		if (t == C_GROW) {
			// Кубы в воздухе становятся большими.
			for (int i = 0; i < s.floats.size(); i++) {
				patchDisplay(w, s.floats.get(i), animNbt(cubeTransform(floatOffset(i, false), 1.8f), 8));
			}
			sound(w, c, SoundEvents.ENTITY_WARDEN_ROAR, 2.0f, 0.7f);
		}
		if (t == C_FLY) {
			// ...и летят на игрока, превращаясь в грани.
			animatePanels(s, 1.25f, PANEL_SIZE, C_IMPACT - C_FLY);
			sound(w, c, SoundEvents.ENTITY_ENDER_DRAGON_FLAP, 2.0f, 0.6f);
		}
		if (t > C_FLY && t < C_IMPACT) {
			w.spawnParticles(RED_DUST, c.x, c.y, c.z, 10, 1.8, 1.8, 1.8, 0.0);
		}
		if (t == C_ARMS_BACK) {
			for (Arm arm : s.arms) {
				patchDisplay(w, arm.id, animNbt(armTransform(arm, 0.02f), 6));
			}
		}
		if (t == C_IMPACT) {
			removeArms(s);
			w.spawnParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 4, 0.8, 0.8, 0.8, 0.0);
			w.spawnParticles(RED_DUST, c.x, c.y, c.z, 60, 1.4, 1.4, 1.4, 0.0);
			sound(w, c, SoundEvents.BLOCK_ANVIL_LAND, 2.0f, 0.5f);
		}
		if (t == C_COMPRESS) {
			animatePanels(s, 0.62f, 1.5f, C_FINISH - C_COMPRESS - 3);
		}
		if (t > C_COMPRESS && t % 6 == 0) {
			sound(w, c, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 2.0f, 0.8f);
			w.spawnParticles(DARK_RED_DUST, c.x, c.y, c.z, 10, 0.7, 0.7, 0.7, 0.0);
		}
	}

	/** Убирает всю анимацию и отпускает цель. */
	private static void abortSeal(ServerPlayerEntity p, Seal s) {
		discard(s.world, s.eyeId);
		removeArms(s);
		for (UUID id : s.floats) {
			discard(s.world, id);
		}
		s.floats.clear();
		if (p != null) {
			p.removeStatusEffect(StatusEffects.SLOWNESS);
		}
	}

	private static void finishSeal(ServerPlayerEntity p, Seal s) {
		ServerWorld w = s.world;
		Vec3d c = s.center;

		w.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0, 0, 0, 0);
		w.spawnParticles(BLUE_DUST, c.x, c.y, c.z, 50, 0.8, 0.8, 0.8, 0.0);
		sound(w, c, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 2.0f, 0.6f);
		sound(w, c, SoundEvents.BLOCK_BEACON_POWER_SELECT, 2.0f, 0.7f);

		// Красные грани исчезают, на месте остаётся голубой куб.
		abortSeal(null, s);
		UUID sealed = spawnDisplay(w, EntityType.BLOCK_DISPLAY, c,
				"{block_state:{Name:\"" + SEALED_BLOCK + "\"},brightness:{sky:15,block:15},"
						+ "Glowing:1b,glow_color_override:" + SEALED_GLOW + ",Tags:[\"prisonrealm_cube\"],"
						+ spinTransform(SEALED_SIZE, 0.0f) + "}");

		p.server.getPlayerManager().broadcast(Text.translatable("prisonrealm.sealed", p.getDisplayName()), false);
		if (!arrest(p, SEAL_SECONDS, sealed)) {
			discard(w, sealed);
		}
	}

	// ============================================================ арест

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

		Seal pending = PENDING.remove(p.getUuid());
		if (pending != null) {
			abortSeal(null, pending);
		}
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

	// ===================================================== освобождение

	public static void release(ServerPlayerEntity p, boolean escaped) {
		release(p, escaped ? Reason.ESCAPE : Reason.TIME);
	}

	private static void release(ServerPlayerEntity p, Reason reason) {
		MinecraftServer server = p.server;
		PrisonState state = PrisonState.get(server);
		PrisonState.Entry e = state.remove(p.getUuid());
		if (e == null) {
			return;
		}
		Seal pending = PENDING.remove(p.getUuid());
		if (pending != null) {
			abortSeal(p, pending);
		}

		RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, e.dimension);
		ServerWorld target = server.getWorld(key);
		breakCube(target, e.cubeId, reason);

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

		if (reason == Reason.ESCAPE) {
			// Беглец светится 3 минуты: его легко найти.
			p.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 20 * 180, 0));
			server.getPlayerManager().broadcast(Text.translatable("prisonrealm.escaped", p.getDisplayName()), false);
		} else {
			p.sendMessage(Text.translatable("prisonrealm.released"), false);
		}
	}

	/** Голубой куб на месте ареста разрушается. */
	private static void breakCube(ServerWorld w, UUID cubeId, Reason reason) {
		if (w == null || cubeId == null) {
			return;
		}
		Entity cube = w.getEntity(cubeId);
		if (cube == null) {
			return;
		}
		Vec3d pos = cube.getPos();
		switch (reason) {
			case OPENED -> {
				w.spawnParticles(ParticleTypes.FLASH, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
				w.spawnParticles(BLUE_DUST, pos.x, pos.y, pos.z, 80, 1.0, 1.0, 1.0, 0.0);
				w.spawnParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z, 30, 0.2, 0.2, 0.2, 0.2);
				sound(w, pos, SoundEvents.BLOCK_BEACON_ACTIVATE, 2.0f, 1.2f);
				sound(w, pos, SoundEvents.BLOCK_GLASS_BREAK, 1.5f, 0.8f);
			}
			case ESCAPE -> {
				w.spawnParticles(ParticleTypes.EXPLOSION, pos.x, pos.y, pos.z, 3, 0.8, 0.8, 0.8, 0.0);
				w.spawnParticles(BLUE_DUST, pos.x, pos.y, pos.z, 40, 0.8, 0.8, 0.8, 0.0);
				sound(w, pos, SoundEvents.BLOCK_GLASS_BREAK, 2.0f, 0.6f);
			}
			default -> {
				w.spawnParticles(ParticleTypes.POOF, pos.x, pos.y, pos.z, 20, 0.6, 0.6, 0.6, 0.02);
				sound(w, pos, SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, 1.5f, 0.6f);
			}
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

	// ============================================================== тик

	public static void tick(MinecraftServer server) {
		tickPending(server);
		if (server.getTicks() % 5 == 0) {
			tickSealedCubes(server);
		}
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
				release(p, Reason.TIME);
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

	/** Голубые кубы медленно вращаются, светятся и подсказывают, что сказать «open». */
	private static void tickSealedCubes(MinecraftServer server) {
		PrisonState state = PrisonState.get(server);
		if (state.isEmpty()) {
			return;
		}
		int step = (server.getTicks() / 5) % 24;
		float angle = (float) Math.toRadians(step * 15.0);
		boolean hint = server.getTicks() % 40 == 0;

		for (Map.Entry<UUID, PrisonState.Entry> me : state.all().entrySet()) {
			PrisonState.Entry e = me.getValue();
			if (e.cubeId == null) {
				continue;
			}
			ServerWorld w = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, e.dimension));
			if (w == null || w.getEntity(e.cubeId) == null) {
				continue;
			}
			patchDisplay(w, e.cubeId, animNbt(spinTransform(SEALED_SIZE, angle), 5));
			if (!hint) {
				continue;
			}
			Vec3d c = new Vec3d(e.x, e.y + 0.9, e.z);
			w.spawnParticles(BLUE_DUST, c.x, c.y, c.z, 6, 0.7, 0.7, 0.7, 0.0);
			w.spawnParticles(ParticleTypes.GLOW, c.x, c.y, c.z, 2, 0.6, 0.6, 0.6, 0.0);
			ServerPlayerEntity prisoner = server.getPlayerManager().getPlayer(me.getKey());
			Text name = prisoner != null ? prisoner.getDisplayName() : Text.translatable("prisonrealm.someone");
			for (ServerPlayerEntity near : w.getPlayers()) {
				if (near.getPos().add(0, 0.9, 0).distanceTo(c) <= OPEN_RANGE + 2.0) {
					near.sendMessage(Text.translatable("prisonrealm.cube_hint", name), true);
				}
			}
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
			Seal s = entry.getValue();
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(entry.getKey());
			if (p == null || p.isDead() || p.getServerWorld() != s.world) {
				// Цель вышла, погибла или сменила мир: печать рассеивается.
				abortSeal(p, s);
				it.remove();
				continue;
			}
			s.age++;
			if (s.closeAt < 0 && s.age >= HOLD_TIMEOUT) {
				abortSeal(p, s);
				it.remove();
				ServerPlayerEntity owner = server.getPlayerManager().getPlayer(s.owner);
				if (owner != null) {
					owner.sendMessage(Text.translatable("prisonrealm.seal_timeout"), true);
				}
				sound(s.world, s.center, SoundEvents.BLOCK_BEACON_DEACTIVATE, 1.5f, 0.6f);
				continue;
			}
			if (s.closeAt >= 0 && s.age - s.closeAt >= C_FINISH) {
				it.remove();
				ready.add(p);
				readySeals.add(s);
			} else {
				stepSeal(p, s);
			}
		}
		for (int i = 0; i < ready.size(); i++) {
			finishSeal(ready.get(i), readySeals.get(i));
		}
	}
}
