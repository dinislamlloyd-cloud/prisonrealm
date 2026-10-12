package com.prisonrealm.prison;

import com.prisonrealm.PrisonRealmMod;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Сохраняемый список заключённых: оставшееся время, где игрок был раньше и его вещи. */
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
		/** Куб-печать, висящий на месте ареста (может быть null). */
		public UUID cubeId;
	}

	private final Map<UUID, Entry> prisoners = new HashMap<>();

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

	public boolean isEmpty() {
		return prisoners.isEmpty();
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
		return state;
	}
}
