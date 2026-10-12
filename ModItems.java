package com.prisonrealm;

import com.prisonrealm.item.GuardBatonItem;
import com.prisonrealm.item.HandcuffsItem;
import com.prisonrealm.item.SealingCubeItem;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModItems {
	public static final Item PRISON_KEY = register("prison_key",
			new Item(new FabricItemSettings().maxCount(16)));

	public static final Item HANDCUFFS = register("handcuffs",
			new HandcuffsItem(new FabricItemSettings().maxDamage(16)));

	public static final Item GUARD_BATON = register("guard_baton",
			new GuardBatonItem(new FabricItemSettings()));

	public static final Item SEALING_CUBE = register("sealing_cube",
			new SealingCubeItem(new FabricItemSettings().maxCount(1)));

	private ModItems() {
	}

	private static Item register(String name, Item item) {
		return Registry.register(Registries.ITEM, new Identifier(PrisonRealmMod.MOD_ID, name), item);
	}

	public static void register() {
		// Вызов нужен, чтобы загрузился класс и выполнились статические инициализаторы.
	}
}
