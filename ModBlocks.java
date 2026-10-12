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

	// Блоки только для анимации куба: без предметов, в мире не ставятся.
	/** Красная плоть с глазом: кубы в воздухе и грани запечатывающего куба. */
	public static final Block SEAL_FLESH = registerHidden("seal_flesh",
			new Block(FabricBlockSettings.copyOf(Blocks.NETHER_WART_BLOCK)));

	/** Большой глаз в центре печати-креста. */
	public static final Block SEAL_EYE = registerHidden("seal_eye",
			new Block(FabricBlockSettings.copyOf(Blocks.NETHER_WART_BLOCK)));

	/** Красная плоть без глаза: лапы и кубы на концах креста. */
	public static final Block SEAL_SKIN = registerHidden("seal_skin",
			new Block(FabricBlockSettings.copyOf(Blocks.NETHER_WART_BLOCK)));

	/** Тёмно-красные щупальца. */
	public static final Block SEAL_TENDON = registerHidden("seal_tendon",
			new Block(FabricBlockSettings.copyOf(Blocks.NETHER_WART_BLOCK)));

	/** Голубой куб, в котором сидит запечатанный. */
	public static final Block SEALED_CUBE = registerHidden("sealed_cube",
			new Block(FabricBlockSettings.copyOf(Blocks.LIGHT_BLUE_CONCRETE)));

	private ModBlocks() {
	}

	private static Block registerHidden(String name, Block block) {
		return Registry.register(Registries.BLOCK, new Identifier(PrisonRealmMod.MOD_ID, name), block);
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
