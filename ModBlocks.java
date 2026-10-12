package com.prisonrealm;

import com.prisonrealm.block.LooseBrickBlock;
import com.prisonrealm.block.PrisonBarsBlock;
import com.prisonrealm.block.PrisonGateBlock;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModBlocks {
	public static final Block PRISON_BRICKS = register("prison_bricks",
			new Block(FabricBlockSettings.copyOf(Blocks.STONE_BRICKS)));

	public static final Block PRISON_BARS = register("prison_bars",
			new PrisonBarsBlock(FabricBlockSettings.copyOf(Blocks.IRON_BARS)));

	public static final Block PRISON_LAMP = register("prison_lamp",
			new Block(FabricBlockSettings.copyOf(Blocks.GLOWSTONE).luminance(15)));

	/** Расшатанный кирпич в камере: из него заключённый достаёт ключ. */
	public static final Block LOOSE_BRICK = register("loose_brick",
			new LooseBrickBlock(FabricBlockSettings.copyOf(Blocks.BEDROCK)));

	/** Выход из тюрьмы: открывается вторым ключом. */
	public static final Block PRISON_GATE = register("prison_gate",
			new PrisonGateBlock(FabricBlockSettings.copyOf(Blocks.BEDROCK)));

	private ModBlocks() {
	}

	private static Block register(String name, Block block) {
		Identifier id = new Identifier(PrisonRealmMod.MOD_ID, name);
		Registry.register(Registries.ITEM, id, new BlockItem(block, new FabricItemSettings()));
		return Registry.register(Registries.BLOCK, id, block);
	}

	public static void register() {
		// Вызов нужен, чтобы загрузился класс и выполнились статические инициализаторы.
	}
}
