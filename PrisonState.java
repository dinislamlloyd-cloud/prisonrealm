package com.prisonrealm.prison;

import com.prisonrealm.PrisonRealmMod;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Сохраняемые данные: заключённые игроки (срок, где были, вещи)
 * и мобы, запечатанные в голубых кубах.
 */
public class PrisonState extends PersistentState {
	public static class Entry {
		public int ticksLeft;
		public Identifier dimension = new Identifier("minecraft", "overworld");
		public double x;
		public double y;
		public double z;
		public float yaw;
		public float pitch;
		public int gameMode;
		public NbtList inventory = new NbtList();
		/** Голубой куб на месте запечатывания (может быть null). */
		public UUID cubeId;
	}

	/** Моб внутри голубого куба. */
	public static class SealedMob {
		public UUID cubeId;
		public Identifier dimension = new Identifier("minecraft", "overworld");
		/** Где моб стоял (сюда же он вернётся). */
		public double x;
		public double y;
		public double z;
		/** Высота центра куба. */
		public double centerY;
		public String name = "";
		public NbtCompound entity = new NbtCompound();
	}

	private final Map<UUID, Entry> prisoners = new HashMap<>();
	private final List<SealedMob> mobs = new ArrayList<>();

	public static PrisonState get(MinecraftServer server) {
		PersistentStateManager manager = server.getOverworld().getPersistentStateManager();
		return manager.getOrCreate(PrisonState::fromNbt, PrisonState::new, PrisonRealmMod.MOD_ID + "_prisoners");
	}

	public boolean has(UUID id) {
		return prisoners.containsKey(id);
	}

	public Entry get(UUID id) {
		return prisoners.get(id);
	}

	public void put(UUID id, Entry entry) {
		prisoners.put(id, entry);
		markDirty();
	}

	public Entry remove(UUID id) {
		Entry removed = prisoners.remove(id);
		if (removed != null) {
			markDirty();
		}
		return removed;
	}

	public Map<UUID, Entry> all() {
		return prisoners;
	}

	/** Нет заключённых игроков (мобы в кубах не считаются). */
	public boolean isEmpty() {
		return prisoners.isEmpty();
	}

	public List<SealedMob> mobs() {
		return mobs;
	}

	public void addMob(SealedMob mob) {
		mobs.add(mob);
		markDirty();
	}

	public void removeMob(SealedMob mob) {
		if (mobs.remove(mob)) {
			markDirty();
		}
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		NbtList list = new NbtList();
		for (Map.Entry<UUID, Entry> me : prisoners.entrySet()) {
			Entry e = me.getValue();
			NbtCompound c = new NbtCompound();
			c.putUuid("Id", me.getKey());
			c.putInt("Ticks", e.ticksLeft);
			c.putString("Dim", e.dimension.toString());
			c.putDouble("X", e.x);
			c.putDouble("Y", e.y);
			c.putDouble("Z", e.z);
			c.putFloat("Yaw", e.yaw);
			c.putFloat("Pitch", e.pitch);
			c.putInt("Mode", e.gameMode);
			c.put("Inventory", e.inventory);
			if (e.cubeId != null) {
				c.putUuid("Cube", e.cubeId);
			}
			list.add(c);
		}
		nbt.put("Prisoners", list);

		NbtList mobList = new NbtList();
		for (SealedMob m : mobs) {
			NbtCompound c = new NbtCompound();
			if (m.cubeId != null) {
				c.putUuid("Cube", m.cubeId);
			}
			c.putString("Dim", m.dimension.toString());
			c.putDouble("X", m.x);
			c.putDouble("Y", m.y);
			c.putDouble("Z", m.z);
			c.putDouble("CenterY", m.centerY);
			c.putString("Name", m.name);
			c.put("Entity", m.entity);
			mobList.add(c);
		}
		nbt.put("Mobs", mobList);
		return nbt;
	}

	public static PrisonState fromNbt(NbtCompound nbt) {
		PrisonState state = new PrisonState();
		NbtList list = nbt.getList("Prisoners", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < list.size(); i++) {
			NbtCompound c = list.getCompound(i);
			Entry e = new Entry();
			e.ticksLeft = c.getInt("Ticks");
			e.dimension = new Identifier(c.getString("Dim"));
			e.x = c.getDouble("X");
			e.y = c.getDouble("Y");
			e.z = c.getDouble("Z");
			e.yaw = c.getFloat("Yaw");
			e.pitch = c.getFloat("Pitch");
			e.gameMode = c.getInt("Mode");
			e.inventory = c.getList("Inventory", NbtElement.COMPOUND_TYPE);
			if (c.containsUuid("Cube")) {
				e.cubeId = c.getUuid("Cube");
			}
			state.prisoners.put(c.getUuid("Id"), e);
		}

		NbtList mobList = nbt.getList("Mobs", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < mobList.size(); i++) {
			NbtCompound c = mobList.getCompound(i);
			SealedMob m = new SealedMob();
			if (c.containsUuid("Cube")) {
				m.cubeId = c.getUuid("Cube");
			}
			m.dimension = new Identifier(c.getString("Dim"));
			m.x = c.getDouble("X");
			m.y = c.getDouble("Y");
			m.z = c.getDouble("Z");
			m.centerY = c.getDouble("CenterY");
			m.name = c.getString("Name");
			m.entity = c.getCompound("Entity");
			state.mobs.add(m);
		}
		return state;
	}
}
