package com.prisonrealm.prison;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.prisonrealm.ModItems;
import com.prisonrealm.PrisonRealmMod;
import com.prisonrealm.item.SealingCubeItem;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Вся логика ареста, запечатывания кубом, срока, освобождения и побега.
 *
 * Запечатывание кубом (игроки и мобы):
 *  1. ПКМ кубом по цели (или ПКМ в воздух, глядя на цель): появляется куб-глаз,
 *     вокруг цели зависают шесть красных кубов с глазами, от каждого к цели
 *     тянутся щупальца и держат её на месте.
 *  2. Ещё раз ПКМ кубом (или «close» в чат): кубы растут, летят на цель
 *     и смыкаются вокруг неё. Цель исчезает внутри, куб сжимается.
 *  3. Остаётся голубой куб. Любой игрок в 2 блоках пишет «open», и цель выходит.
 */
public final class PrisonManager {
	/** Срок по наручникам. */
	public static final int DEFAULT_SECONDS = 300;
	/** Срок по запечатывающему кубу (если никто не скажет «open»). */
	public static final int SEAL_SECONDS = 600;

	// Фаза захвата (тики от начала).
	private static final int UNFOLD_TIME = 14;
	private static final int ARMS_SPAWN_AT = 14;
	private static final int FLOATS_SPAWN_AT = 18;
	private static final int FLOAT_ARMS_AT = 28;
	private static final int READY_AT = 40;
	/** Сколько ждать закрытия, прежде чем печать рассеется: 20 секунд. */
	private static final int HOLD_TIMEOUT = 400;

	// Фаза закрытия (тики от момента «close»).
	private static final int C_FLY = 8;
	private static final int C_CLUSTER = 16;
	private static final int C_CONVERGE = 28;
	private static final int C_CAPTURE = 34;
	private static final int C_COMPRESS = 37;
	private static final int C_FINISH = 58;

	private static final float PANEL_THICKNESS = 0.3f;
	private static final float FLOAT_SIZE = 0.8f;
	private static final float FLOAT_BIG = 1.6f;
	/** Размер кубов, навалившихся на цель (как на 2-м кадре). */
	private static final float CLUSTER_SIZE = 1.45f;
	/** Насколько близко надо стоять к голубому кубу, чтобы сказать «open». */
	private static final double OPEN_RANGE = 2.0;
	private static final double LOOK_RANGE = 12.0;

	private static final Set<String> CLOSE_WORDS = Set.of("close", "закрыть", "закройся", "закрой");
	private static final Set<String> OPEN_WORDS = Set.of("open", "открыть", "откройся", "открой");

	private static final String FLESH_BLOCK = "prisonrealm:seal_flesh";
	private static final String EYE_BLOCK = "prisonrealm:seal_eye";
	private static final String SKIN_BLOCK = "prisonrealm:seal_skin";
	private static final String TENDON_BLOCK = "prisonrealm:seal_tendon";
	private static final String SEALED_BLOCK = "prisonrealm:sealed_cube";
	/** Голубое свечение вокруг готового куба. */
	private static final int SEALED_GLOW = 0x55FFFF;
	private static final float SEALED_SIZE = 1.3f;

	private static final DustParticleEffect RED_DUST =
			new DustParticleEffect(new Vector3f(0.8f, 0.03f, 0.03f), 1.5f);
	private static final DustParticleEffect DARK_RED_DUST =
			new DustParticleEffect(new Vector3f(0.35f, 0.0f, 0.02f), 2.0f);
	private static final DustParticleEffect BLUE_DUST =
			new DustParticleEffect(new Vector3f(0.35f, 0.85f, 1.0f), 1.3f);

	private static final class Seal {
		final ServerWorld world;
		final UUID owner;
		final UUID target;
		final boolean isPlayer;
		final Vec3d anchor;
		final Vec3d center;
		final Vec3d eyePos;
		/** Половина размера сомкнутого куба: чтобы цель целиком помещалась внутри. */
		final float radius;
		final float halfHeight;
		final boolean prevNoAi;
		Text name;
		int age = 0;
		/** Поворот «печати»-креста: лицом к цели. */
		Quaternionf facing = new Quaternionf();
		/** Детали креста: 0 — глаз в центре, 1..4 — лапы, 5..8 — кубы на концах лап. */
		final List<UUID> cross = new ArrayList<>();
		final List<UUID> eyeArms = new ArrayList<>();
		final List<UUID> floats = new ArrayList<>();
		final List<UUID> floatArms = new ArrayList<>();
		boolean closeRequested;
		int closeAt = -1;
		/** Цель уже внутри куба (игрок в тюрьме или моб сохранён). */
		boolean captured;
		PrisonState.SealedMob mob;

		Seal(ServerWorld world, UUID owner, LivingEntity target, Vec3d eyePos) {
			this.world = world;
			this.owner = owner;
			this.target = target.getUuid();
			this.isPlayer = target instanceof ServerPlayerEntity;
			this.anchor = target.getPos();
			this.halfHeight = target.getHeight() / 2;
			this.center = anchor.add(0, halfHeight, 0);
			this.radius = Math.max(1.25f, Math.max(target.getWidth(), target.getHeight()) / 2 + 0.35f);
			this.eyePos = eyePos;
			this.prevNoAi = target instanceof MobEntity m && m.isAiDisabled();
			this.name = target.getDisplayName();
		}
	}

	/** Цели, которые сейчас держит куб (ключ: UUID цели). */
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

	// ======================================================== предмет

	/** ПКМ кубом в воздух: закрыть свою печать или схватить цель, на которую смотрим. */
	public static boolean useCube(ServerPlayerEntity owner) {
		if (requestClose(owner)) {
			return true;
		}
		LivingEntity target = lookTarget(owner);
		if (target == null) {
			owner.sendMessage(Text.translatable("prisonrealm.no_target"), true);
			return false;
		}
		return useCubeOn(owner, target);
	}

	/** ПКМ кубом по сущности. */
	public static boolean useCubeOn(ServerPlayerEntity owner, LivingEntity target) {
		if (requestClose(owner)) {
			return true;
		}
		if (isPrisoner(owner)) {
			return false;
		}
		if (!canSeal(target)) {
			owner.sendMessage(Text.translatable("prisonrealm.cannot_seal"), true);
			return false;
		}
		return beginSeal(target, owner, false);
	}

	/** Если у игрока есть печать, ждущая закрытия, закрывает её. */
	private static boolean requestClose(ServerPlayerEntity owner) {
		for (Seal s : PENDING.values()) {
			if (s.owner.equals(owner.getUuid()) && s.closeAt < 0) {
				s.closeRequested = true;
				return true;
			}
		}
		return false;
	}

	private static boolean canSeal(Entity e) {
		if (!(e instanceof LivingEntity living) || !living.isAlive() || PENDING.containsKey(e.getUuid())) {
			return false;
		}
		if (e instanceof ServerPlayerEntity p) {
			return !p.isSpectator() && !isPrisoner(p);
		}
		return e instanceof MobEntity && !(e instanceof EnderDragonEntity) && !(e instanceof WitherEntity);
	}

	private static LivingEntity lookTarget(ServerPlayerEntity src) {
		Vec3d eye = src.getEyePos();
		Vec3d look = src.getRotationVec(1.0f);
		LivingEntity best = null;
		double bestDot = 0.96;
		for (LivingEntity o : src.getServerWorld().getEntitiesByClass(LivingEntity.class,
				src.getBoundingBox().expand(LOOK_RANGE), e -> e != src && canSeal(e))) {
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
		if (isPrisoner(sender) || requestClose(sender)) {
			return;
		}
		// С кубом в руке, глядя на цель: схватить и сразу закрыть.
		boolean holding = sender.getMainHandStack().isOf(ModItems.SEALING_CUBE)
				|| sender.getOffHandStack().isOf(ModItems.SEALING_CUBE);
		if (!holding) {
			return;
		}
		if (sender.getItemCooldownManager().isCoolingDown(ModItems.SEALING_CUBE)) {
			sender.sendMessage(Text.translatable("prisonrealm.cube_cooldown"), true);
			return;
		}
		LivingEntity target = lookTarget(sender);
		if (target == null) {
			sender.sendMessage(Text.translatable("prisonrealm.no_target"), true);
			return;
		}
		beginSeal(target, sender, true);
	}

	private static void handleOpen(ServerPlayerEntity sender) {
		if (isPrisoner(sender)) {
			return;
		}
		MinecraftServer server = sender.server;
		PrisonState state = PrisonState.get(server);
		ServerWorld w = sender.getServerWorld();
		Identifier senderDim = w.getRegistryKey().getValue();
		Vec3d me = sender.getPos().add(0, 0.9, 0);
		double reach = OPEN_RANGE + SEALED_SIZE / 2 + 0.3;

		for (Map.Entry<UUID, PrisonState.Entry> en : new ArrayList<>(state.all().entrySet())) {
			PrisonState.Entry e = en.getValue();
			if (e.cubeId == null || !e.dimension.equals(senderDim)
					|| me.distanceTo(new Vec3d(e.x, e.y + 0.9, e.z)) > reach) {
				continue;
			}
			ServerPlayerEntity prisoner = server.getPlayerManager().getPlayer(en.getKey());
			Text name = prisoner != null ? prisoner.getDisplayName() : Text.translatable("prisonrealm.someone");
			server.getPlayerManager().broadcast(Text.translatable("prisonrealm.opened_by", sender.getDisplayName(), name), false);
			if (prisoner != null) {
				release(prisoner, Reason.OPENED);
			} else {
				// Заключённый не в сети: куб открываем сейчас, освободится при входе.
				breakCube(w, e.cubeId, Reason.OPENED);
				e.cubeId = null;
				e.ticksLeft = 0;
				state.markDirty();
			}
			return;
		}

		for (PrisonState.SealedMob m : new ArrayList<>(state.mobs())) {
			if (!m.dimension.equals(senderDim) || me.distanceTo(new Vec3d(m.x, m.centerY, m.z)) > reach) {
				continue;
			}
			server.getPlayerManager().broadcast(
					Text.translatable("prisonrealm.opened_by", sender.getDisplayName(), Text.literal(m.name)), false);
			releaseMob(w, state, m);
			return;
		}
	}

	private static void releaseMob(ServerWorld w, PrisonState state, PrisonState.SealedMob m) {
		breakCube(w, m.cubeId, Reason.OPENED);
		state.removeMob(m);
		NbtCompound nbt = m.entity.copy();
		nbt.remove("UUID");
		Optional<Entity> restored = EntityType.getEntityFromNbt(nbt, w);
		if (restored.isPresent()) {
			Entity e = restored.get();
			e.refreshPositionAndAngles(m.x, m.y, m.z, e.getYaw(), e.getPitch());
			w.spawnEntity(e);
		} else {
			PrisonRealmMod.LOGGER.warn("Could not restore sealed mob {}", m.name);
		}
	}

	// ====================================================== запечатывание

	public static boolean beginSeal(LivingEntity target, ServerPlayerEntity owner) {
		return beginSeal(target, owner, false);
	}

	/** Куб хватает цель. Закрывается по второму ПКМ / «close» (или сразу, если closeNow). */
	public static boolean beginSeal(LivingEntity target, ServerPlayerEntity owner, boolean closeNow) {
		if (!canSeal(target)) {
			return false;
		}
		ServerWorld w = (ServerWorld) target.getWorld();

		// Куб-глаз парит перед целью, на линии «цель -> владелец куба».
		Vec3d eye = target.getEyePos();
		Vec3d toOwner = owner == target ? target.getRotationVec(1.0f) : owner.getEyePos().subtract(eye);
		Vec3d flat = new Vec3d(toOwner.x, 0, toOwner.z);
		if (flat.lengthSquared() < 1.0E-4) {
			flat = new Vec3d(0, 0, 1);
		}
		float extra = Math.max(0.0f, Math.max(target.getWidth(), target.getHeight()) / 2 - 0.9f);
		Vec3d eyePos = eye.add(flat.normalize().multiply(3.6 + extra)).add(0, 0.4, 0);

		Seal s = new Seal(w, owner.getUuid(), target, eyePos);
		s.closeRequested = closeNow;
		// «Печать» стоит лицом к цели: локальная ось +Z смотрит на цель.
		Vec3d toTarget = s.center.subtract(eyePos);
		s.facing = new Quaternionf().rotationY((float) Math.atan2(toTarget.x, toTarget.z));
		for (int i = 0; i < 9; i++) {
			s.cross.add(spawnDisplay(w, EntityType.BLOCK_DISPLAY, eyePos,
					blockDisplayNbt(i == 0 ? EYE_BLOCK : SKIN_BLOCK, crossTransform(s, i, false))));
		}
		PENDING.put(target.getUuid(), s);

		// Замедление без частиц и значка: никаких зелёных спиралей.
		target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, HOLD_TIMEOUT + 80, 10, false, false, false));
		if (target instanceof MobEntity mob) {
			mob.setAiDisabled(true);
		}
		sound(w, eyePos, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 1.5f, 0.5f);
		if (target instanceof ServerPlayerEntity tp) {
			tp.sendMessage(Text.translatable("prisonrealm.being_sealed"), true);
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

	private static void discardAll(ServerWorld w, List<UUID> ids) {
		for (UUID id : ids) {
			discard(w, id);
		}
		ids.clear();
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

	/** Куб размера size с центром в offset, повёрнутый на rot. */
	private static String cubeTransform(Vec3d offset, float size, Quaternionf rot) {
		Vector3f half = new Vector3f(size / 2, size / 2, size / 2);
		rot.transform(half);
		Vector3f t = new Vector3f((float) offset.x - half.x, (float) offset.y - half.y, (float) offset.z - half.z);
		return transformNbt(t, rot, new Vector3f(size, size, size));
	}

	/** Куб размера size, повёрнутый на angle вокруг вертикали, с центром в точке сущности. */
	private static String spinTransform(float size, float angle) {
		return cubeTransform(Vec3d.ZERO, size, new Quaternionf().rotationY(angle));
	}

	/** Палка толщиной thick из точки сущности в направлении dir длиной length. */
	private static String rodTransform(Vec3d dir, float length, float thick) {
		Vec3d n = dir.lengthSquared() < 1.0E-6 ? new Vec3d(0, 1, 0) : dir.normalize();
		Quaternionf rot = new Quaternionf().rotationTo(new Vector3f(0, 0, 1),
				new Vector3f((float) n.x, (float) n.y, (float) n.z));
		Vector3f t = new Vector3f(thick / 2, thick / 2, 0.0f);
		rot.transform(t);
		t.negate();
		return transformNbt(t, rot, new Vector3f(thick, thick, Math.max(0.02f, length)));
	}

	private static void sound(ServerWorld w, Vec3d pos, SoundEvent event, float volume, float pitch) {
		w.playSound(null, BlockPos.ofFloored(pos), event, SoundCategory.HOSTILE, volume, pitch);
	}

	// ---- геометрия ----

	/** Точки на теле цели, за которые хватают щупальца печати (без головы, чтобы не лезли в камеру). */
	private static Vec3d[] grabPoints(Seal s) {
		double h = s.halfHeight;
		double w = Math.min(0.4, s.radius * 0.3);
		return new Vec3d[] {
				new Vec3d(w, h * 0.35, 0), new Vec3d(-w, h * 0.35, 0),
				new Vec3d(w * 0.5, -h * 0.6, 0), new Vec3d(-w * 0.5, -h * 0.6, 0)
		};
	}

	/** Откуда растут щупальца печати: из кубов на концах лап. */
	private static Vec3d crossKnob(Seal s, int k) {
		double a = Math.toRadians(45 + 90 * k);
		Vector3f local = new Vector3f((float) (Math.cos(a) * 1.75), (float) (Math.sin(a) * 1.75), 0.0f);
		s.facing.transform(local);
		return s.eyePos.add(local.x, local.y, local.z);
	}

	/**
	 * Деталь печати-креста (как на 3-м кадре). open=false: всё сложено в маленький куб.
	 * 0 — пластина с глазом, 1..4 — лапы по диагоналям, 5..8 — кубы на концах лап.
	 */
	private static String crossTransform(Seal s, int idx, boolean open) {
		Vector3f lt;
		Quaternionf lr;
		Vector3f scale;
		if (idx == 0) {
			float p = open ? 1.9f : 0.6f;
			float th = open ? 0.3f : 0.6f;
			lr = new Quaternionf();
			scale = new Vector3f(p, p, th);
			lt = new Vector3f(-p / 2, -p / 2, -th / 2);
		} else if (idx <= 4) {
			double a = Math.toRadians(45 + 90 * (idx - 1));
			float len = open ? 1.55f : 0.02f;
			float wid = 0.55f;
			float dep = 0.32f;
			lr = new Quaternionf().rotationZ((float) a);
			scale = new Vector3f(len, wid, dep);
			lt = new Vector3f(0, -wid / 2, -dep / 2);
			lr.transform(lt);
		} else {
			double a = Math.toRadians(45 + 90 * (idx - 5));
			float dist = open ? 1.75f : 0.0f;
			float size = open ? 0.75f : 0.3f;
			lr = new Quaternionf().rotationZ((float) a);
			Vector3f half = new Vector3f(-size / 2, -size / 2, -size / 2);
			lr.transform(half);
			lt = new Vector3f((float) (Math.cos(a) * dist), (float) (Math.sin(a) * dist), 0).add(half);
			scale = new Vector3f(size, size, size);
		}
		// Переводим из локальных координат печати в мировые (поворот лицом к цели).
		Vector3f t = new Vector3f(lt);
		s.facing.transform(t);
		Quaternionf rot = new Quaternionf(s.facing).mul(lr);
		return transformNbt(t, rot, scale);
	}

	private static void animateCross(Seal s, boolean open, int duration) {
		for (int i = 0; i < s.cross.size(); i++) {
			patchDisplay(s.world, s.cross.get(i), animNbt(crossTransform(s, i, open), duration));
		}
	}

	/** Где висит i-й куб в ожидании закрытия (относительно центра цели). */
	private static Vec3d floatOffset(Seal s, int i, boolean up) {
		double ang = Math.toRadians(i * 60 + 30);
		double ring = s.radius + 1.9;
		double y = s.halfHeight * 0.6 + 0.4 + (i % 2) * 0.7 + (up ? 0.25 : 0.0);
		return new Vec3d(Math.cos(ang) * ring, y, Math.sin(ang) * ring);
	}

	/** Каждый куб висит чуть наискось, чтобы выглядело живее. */
	private static Quaternionf floatRotation(int i) {
		return new Quaternionf().rotationXYZ(
				(float) Math.toRadians(15 + i * 11), (float) Math.toRadians(i * 37), (float) Math.toRadians(-10 + i * 7));
	}

	/** Куда i-й куб наваливается на цель: со всех сторон вплотную, вразнобой (как на 2-м кадре). */
	private static Vec3d clusterOffset(Seal s, int i) {
		double r = s.radius * 0.78;
		double h = s.halfHeight;
		return switch (i) {
			case 0 -> new Vec3d(r, h * 0.35, 0.25);
			case 1 -> new Vec3d(-r, -h * 0.2, -0.2);
			case 2 -> new Vec3d(0.2, h + CLUSTER_SIZE * 0.35, -0.15);
			case 3 -> new Vec3d(-0.25, -h * 0.75, r * 0.55);
			case 4 -> new Vec3d(-0.3, h * 0.25, r);
			default -> new Vec3d(0.3, -h * 0.15, -r);
		};
	}

	/** Наклон навалившегося куба: меньше, чем в воздухе. */
	private static Quaternionf clusterRotation(int i) {
		return new Quaternionf().rotationXYZ(
				(float) Math.toRadians(8 + i * 5), (float) Math.toRadians(i * 19 - 20), (float) Math.toRadians(-6 + i * 4));
	}

	/** Где на теле крепится щупальце i-го куба. */
	private static Vec3d floatAttach(Seal s, int i) {
		Vec3d o = floatOffset(s, i, false);
		Vec3d flat = new Vec3d(o.x, 0, o.z).normalize().multiply(0.25);
		return s.center.add(flat).add(0, (i % 3 - 1) * s.halfHeight * 0.5, 0);
	}

	/** Грань сомкнутого куба: face 0..5 = +X, -X, +Y, -Y, +Z, -Z. Края граней перекрываются, щелей нет. */
	private static String panelTransform(int face, float distance) {
		int axis = face / 2;
		float sign = face % 2 == 0 ? 1.0f : -1.0f;
		float s = (2 * distance + PANEL_THICKNESS) * (1.0f + 0.012f * axis);
		Vector3f scale = new Vector3f(s, s, s);
		Vector3f c = new Vector3f(0, 0, 0);
		if (axis == 0) {
			scale.x = PANEL_THICKNESS;
			c.x = sign * distance;
		} else if (axis == 1) {
			scale.y = PANEL_THICKNESS;
			c.y = sign * distance;
		} else {
			scale.z = PANEL_THICKNESS;
			c.z = sign * distance;
		}
		Vector3f translation = new Vector3f(c.x - scale.x / 2, c.y - scale.y / 2, c.z - scale.z / 2);
		return transformNbt(translation, new Quaternionf(), scale);
	}

	private static void animatePanels(Seal s, float distance, int duration) {
		for (int face = 0; face < s.floats.size(); face++) {
			patchDisplay(s.world, s.floats.get(face), animNbt(panelTransform(face, distance), duration));
		}
	}

	// ---- шаг анимации ----

	private static void stepSeal(LivingEntity target, Seal s) {
		ServerWorld w = s.world;
		Vec3d eye = s.eyePos;
		Vec3d c = s.center;

		// Цель прикована к месту.
		if (target != null && target.getPos().squaredDistanceTo(s.anchor) > 0.04) {
			if (target instanceof ServerPlayerEntity p) {
				p.networkHandler.requestTeleport(s.anchor.x, s.anchor.y, s.anchor.z, p.getYaw(), p.getPitch());
			} else {
				target.requestTeleport(s.anchor.x, s.anchor.y, s.anchor.z);
			}
			target.setVelocity(Vec3d.ZERO);
		}

		// Лёгкая красная дымка вокруг печати.
		if (s.age % 4 == 0 && !s.captured && !s.cross.isEmpty()) {
			double a = s.age * 0.3;
			w.spawnParticles(DARK_RED_DUST,
					eye.x + Math.cos(a) * 1.8, eye.y + Math.sin(a * 0.7) * 1.2, eye.z + Math.sin(a) * 1.8,
					1, 0.05, 0.05, 0.05, 0.0);
		}

		// ---------- захват ----------
		if (s.age == 1) {
			// Печать раскрывается: из маленького куба вырастает крест с глазом.
			animateCross(s, true, UNFOLD_TIME);
			sound(w, eye, SoundEvents.BLOCK_SCULK_SHRIEKER_SHRIEK, 1.5f, 0.6f);
		}
		if (s.age == ARMS_SPAWN_AT) {
			Vec3d[] pts = grabPoints(s);
			for (int k = 0; k < pts.length; k++) {
				Vec3d from = crossKnob(s, k);
				s.eyeArms.add(spawnDisplay(w, EntityType.BLOCK_DISPLAY, from,
						blockDisplayNbt(TENDON_BLOCK, rodTransform(c.add(pts[k]).subtract(from), 0.02f, 0.18f))));
			}
			sound(w, eye, SoundEvents.ENTITY_WARDEN_EMERGE, 1.5f, 0.7f);
		}
		if (s.age == ARMS_SPAWN_AT + 1) {
			Vec3d[] pts = grabPoints(s);
			for (int k = 0; k < s.eyeArms.size(); k++) {
				Vec3d d = c.add(pts[k]).subtract(crossKnob(s, k));
				patchDisplay(w, s.eyeArms.get(k), animNbt(rodTransform(d, (float) d.length(), 0.18f), 10));
			}
		}
		if (s.age == FLOATS_SPAWN_AT) {
			for (int i = 0; i < 6; i++) {
				s.floats.add(spawnDisplay(w, EntityType.BLOCK_DISPLAY, c,
						blockDisplayNbt(FLESH_BLOCK, cubeTransform(floatOffset(s, i, false), 0.01f, floatRotation(i)))));
			}
			sound(w, c, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.5f, 0.6f);
		}
		if (s.age == FLOATS_SPAWN_AT + 1) {
			for (int i = 0; i < s.floats.size(); i++) {
				patchDisplay(w, s.floats.get(i), animNbt(cubeTransform(floatOffset(s, i, false), FLOAT_SIZE, floatRotation(i)), 10));
			}
		}
		// Щупальца от каждого куба к цели.
		if (s.age == FLOAT_ARMS_AT) {
			for (int i = 0; i < s.floats.size(); i++) {
				Vec3d from = floatAttach(s, i);
				Vec3d to = c.add(floatOffset(s, i, false));
				s.floatArms.add(spawnDisplay(w, EntityType.BLOCK_DISPLAY, from,
						blockDisplayNbt(TENDON_BLOCK, rodTransform(to.subtract(from), 0.02f, 0.14f))));
			}
			sound(w, c, SoundEvents.ENTITY_WARDEN_TENDRIL_CLICKS, 2.0f, 0.6f);
		}
		if (s.age == FLOAT_ARMS_AT + 1) {
			for (int i = 0; i < s.floatArms.size(); i++) {
				Vec3d d = c.add(floatOffset(s, i, false)).subtract(floatAttach(s, i));
				patchDisplay(w, s.floatArms.get(i), animNbt(rodTransform(d, (float) d.length(), 0.14f), 10));
			}
		}

		// Кубы покачиваются в воздухе, пока владелец не закроет печать.
		if (s.closeAt < 0 && s.age > READY_AT && s.age % 20 == 0) {
			boolean up = (s.age / 20) % 2 == 0;
			for (int i = 0; i < s.floats.size(); i++) {
				Vec3d o = floatOffset(s, i, up);
				patchDisplay(w, s.floats.get(i), animNbt(cubeTransform(o, FLOAT_SIZE, floatRotation(i)), 20));
				if (i < s.floatArms.size()) {
					Vec3d d = c.add(o).subtract(floatAttach(s, i));
					patchDisplay(w, s.floatArms.get(i), animNbt(rodTransform(d, (float) d.length(), 0.14f), 20));
				}
			}
		}
		if (s.age == READY_AT && !s.closeRequested) {
			ServerPlayerEntity owner = w.getServer().getPlayerManager().getPlayer(s.owner);
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
		if (t == 0) {
			// Кубы в воздухе становятся большими, печать складывается обратно.
			for (int i = 0; i < s.floats.size(); i++) {
				patchDisplay(w, s.floats.get(i), animNbt(cubeTransform(floatOffset(s, i, false), FLOAT_BIG, floatRotation(i)), 8));
			}
			animateCross(s, false, 12);
			sound(w, c, SoundEvents.ENTITY_WARDEN_ROAR, 2.0f, 0.7f);
		}
		if (t == C_FLY) {
			// Кубы летят на цель и наваливаются со всех сторон. Щупальца втягиваются.
			for (int i = 0; i < s.floats.size(); i++) {
				patchDisplay(w, s.floats.get(i), animNbt(cubeTransform(clusterOffset(s, i), CLUSTER_SIZE, clusterRotation(i)), C_CLUSTER - C_FLY));
			}
			for (int i = 0; i < s.floatArms.size(); i++) {
				Vec3d d = c.add(floatOffset(s, i, false)).subtract(floatAttach(s, i));
				patchDisplay(w, s.floatArms.get(i), animNbt(rodTransform(d, 0.02f, 0.14f), C_CLUSTER - C_FLY));
			}
			Vec3d[] pts = grabPoints(s);
			for (int k = 0; k < s.eyeArms.size(); k++) {
				patchDisplay(w, s.eyeArms.get(k), animNbt(rodTransform(c.add(pts[k]).subtract(crossKnob(s, k)), 0.02f, 0.18f), 8));
			}
			sound(w, c, SoundEvents.ENTITY_ENDER_DRAGON_FLAP, 2.0f, 0.6f);
		}
		if (t > C_FLY && t < C_CLUSTER) {
			w.spawnParticles(RED_DUST, c.x, c.y, c.z, 6, s.radius * 1.4, s.radius * 1.4, s.radius * 1.4, 0.0);
		}
		if (t == C_CLUSTER) {
			// Глухой удар: кубы навалились на цель.
			discardAll(w, s.eyeArms);
			discardAll(w, s.floatArms);
			discardAll(w, s.cross);
			w.spawnParticles(RED_DUST, c.x, c.y, c.z, 40, s.radius, s.radius, s.radius, 0.0);
			sound(w, c, SoundEvents.ENTITY_IRON_GOLEM_DAMAGE, 2.0f, 0.5f);
			sound(w, c, SoundEvents.BLOCK_MUD_BREAK, 2.0f, 0.5f);
		}
		if (t == C_CLUSTER + 6) {
			sound(w, c, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 2.0f, 0.7f);
		}
		if (t == C_CONVERGE) {
			// Кубы сдвигаются вплотную и превращаются в один сомкнутый куб.
			animatePanels(s, s.radius, C_CAPTURE - C_CONVERGE);
			sound(w, c, SoundEvents.BLOCK_PISTON_CONTRACT, 2.0f, 0.5f);
		}
		if (t == C_CAPTURE) {
			// Куб сомкнулся: цель исчезает внутри.
			capture(target, s);
			w.spawnParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 3, 0.8, 0.8, 0.8, 0.0);
			w.spawnParticles(RED_DUST, c.x, c.y, c.z, 50, s.radius + 0.3, s.radius + 0.3, s.radius + 0.3, 0.0);
			sound(w, c, SoundEvents.BLOCK_ANVIL_LAND, 2.0f, 0.5f);
		}
		if (t == C_COMPRESS) {
			animatePanels(s, SEALED_SIZE / 2 - PANEL_THICKNESS / 2, C_FINISH - C_COMPRESS - 3);
		}
		if (t > C_COMPRESS && t % 6 == 0) {
			sound(w, c, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 2.0f, 0.8f);
			w.spawnParticles(DARK_RED_DUST, c.x, c.y, c.z, 8, 0.7, 0.7, 0.7, 0.0);
		}
	}

	/** Цель попадает внутрь куба: игрок уходит в тюрьму, моб сохраняется. */
	private static void capture(LivingEntity target, Seal s) {
		s.captured = true;
		MinecraftServer server = s.world.getServer();
		ServerPlayerEntity owner = server.getPlayerManager().getPlayer(s.owner);
		if (owner != null) {
			owner.getItemCooldownManager().set(ModItems.SEALING_CUBE, SealingCubeItem.COOLDOWN_TICKS);
		}
		if (target == null) {
			return;
		}
		server.getPlayerManager().broadcast(Text.translatable("prisonrealm.sealed", s.name), false);

		if (target instanceof ServerPlayerEntity p) {
			arrest(p, SEAL_SECONDS, null);
			return;
		}
		if (target instanceof MobEntity mob) {
			mob.setAiDisabled(s.prevNoAi);
			mob.removeStatusEffect(StatusEffects.SLOWNESS);
			mob.stopRiding();
			mob.removeAllPassengers();
			NbtCompound nbt = new NbtCompound();
			if (mob.saveSelfNbt(nbt)) {
				PrisonState.SealedMob m = new PrisonState.SealedMob();
				m.dimension = s.world.getRegistryKey().getValue();
				m.x = s.anchor.x;
				m.y = s.anchor.y;
				m.z = s.anchor.z;
				m.centerY = s.center.y;
				m.name = s.name.getString();
				m.entity = nbt;
				s.mob = m;
			}
			mob.discard();
		}
	}

	/** Убирает всю анимацию и отпускает цель (если она ещё не внутри). */
	private static void abortSeal(LivingEntity target, Seal s) {
		discardAll(s.world, s.cross);
		discardAll(s.world, s.eyeArms);
		discardAll(s.world, s.floatArms);
		discardAll(s.world, s.floats);
		if (target != null && !s.captured) {
			target.removeStatusEffect(StatusEffects.SLOWNESS);
			if (target instanceof MobEntity mob) {
				mob.setAiDisabled(s.prevNoAi);
			}
		}
	}

	private static void finishSeal(Seal s) {
		ServerWorld w = s.world;
		Vec3d c = s.center;

		w.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0, 0, 0, 0);
		w.spawnParticles(BLUE_DUST, c.x, c.y, c.z, 50, 0.8, 0.8, 0.8, 0.0);
		sound(w, c, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 2.0f, 0.6f);
		sound(w, c, SoundEvents.BLOCK_BEACON_POWER_SELECT, 2.0f, 0.7f);

		// Красные грани исчезают, на месте остаётся голубой куб.
		abortSeal(null, s);
		UUID cube = spawnDisplay(w, EntityType.BLOCK_DISPLAY, c,
				"{block_state:{Name:\"" + SEALED_BLOCK + "\"},brightness:{sky:15,block:15},"
						+ "Glowing:1b,glow_color_override:" + SEALED_GLOW + ",Tags:[\"prisonrealm_cube\"],"
						+ spinTransform(SEALED_SIZE, 0.0f) + "}");

		PrisonState state = PrisonState.get(w.getServer());
		if (s.isPlayer) {
			PrisonState.Entry e = state.get(s.target);
			if (e != null) {
				e.cubeId = cube;
				state.markDirty();
			} else {
				discard(w, cube);
			}
		} else if (s.mob != null) {
			s.mob.cubeId = cube;
			state.addMob(s.mob);
		} else {
			discard(w, cube);
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

		// Если игрока держал куб, а его арестовали другим способом, печать снимается.
		Seal pending = PENDING.get(p.getUuid());
		if (pending != null && !pending.captured) {
			PENDING.remove(p.getUuid());
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
		// Если куб ещё сжимается, анимацию доигрываем, но голубой куб уже не нужен.
		Seal pending = PENDING.get(p.getUuid());
		if (pending != null && !pending.captured) {
			PENDING.remove(p.getUuid());
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

	/** Голубой куб разрушается. */
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
		if (state.isEmpty() && state.mobs().isEmpty()) {
			return;
		}
		float angle = (float) Math.toRadians(((server.getTicks() / 5) % 24) * 15.0);
		boolean hint = server.getTicks() % 40 == 0;

		for (Map.Entry<UUID, PrisonState.Entry> me : state.all().entrySet()) {
			PrisonState.Entry e = me.getValue();
			if (e.cubeId == null) {
				continue;
			}
			ServerPlayerEntity prisoner = server.getPlayerManager().getPlayer(me.getKey());
			Text name = prisoner != null ? prisoner.getDisplayName() : Text.translatable("prisonrealm.someone");
			animateSealedCube(server, e.dimension, e.cubeId, new Vec3d(e.x, e.y + 0.9, e.z), name, angle, hint);
		}
		for (PrisonState.SealedMob m : state.mobs()) {
			animateSealedCube(server, m.dimension, m.cubeId, new Vec3d(m.x, m.centerY, m.z),
					Text.literal(m.name), angle, hint);
		}
	}

	private static void animateSealedCube(MinecraftServer server, Identifier dim, UUID cubeId, Vec3d c,
			Text name, float angle, boolean hint) {
		if (cubeId == null) {
			return;
		}
		ServerWorld w = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, dim));
		if (w == null || w.getEntity(cubeId) == null) {
			return;
		}
		patchDisplay(w, cubeId, animNbt(spinTransform(SEALED_SIZE, angle), 5));
		if (!hint) {
			return;
		}
		w.spawnParticles(BLUE_DUST, c.x, c.y, c.z, 6, 0.7, 0.7, 0.7, 0.0);
		w.spawnParticles(ParticleTypes.GLOW, c.x, c.y, c.z, 2, 0.6, 0.6, 0.6, 0.0);
		for (ServerPlayerEntity near : w.getPlayers()) {
			if (near.getPos().add(0, 0.9, 0).distanceTo(c) <= OPEN_RANGE + 2.0) {
				near.sendMessage(Text.translatable("prisonrealm.cube_hint", name), true);
			}
		}
	}

	private static void tickPending(MinecraftServer server) {
		if (PENDING.isEmpty()) {
			return;
		}
		List<Seal> done = new ArrayList<>();
		Iterator<Map.Entry<UUID, Seal>> it = PENDING.entrySet().iterator();
		while (it.hasNext()) {
			Seal s = it.next().getValue();
			LivingEntity target = null;
			if (!s.captured) {
				Entity e = s.world.getEntity(s.target);
				if (!(e instanceof LivingEntity living) || !living.isAlive()) {
					// Цель вышла, погибла или сменила мир: печать рассеивается.
					abortSeal(null, s);
					it.remove();
					continue;
				}
				target = living;
			}
			s.age++;
			if (s.closeAt < 0 && s.age >= HOLD_TIMEOUT) {
				abortSeal(target, s);
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
				done.add(s);
			} else {
				stepSeal(target, s);
			}
		}
		for (Seal s : done) {
			finishSeal(s);
		}
	}
}
