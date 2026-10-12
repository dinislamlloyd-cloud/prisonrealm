package com.prisonrealm.prison;

import com.prisonrealm.ModBlocks;
import com.prisonrealm.ModItems;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Строит тюрьму в тюремном измерении при первом аресте.
 *
 * Координаты (Y = уровень пола):
 *  - двор 41x41 с высокой стеной по периметру;
 *  - блок камер на севере (5 камер, решётки на z = -8);
 *  - караулка с сундуком (там лежит ключ от ворот);
 *  - ворота на южной стороне (z = +20).
 */
public final class PrisonBuilder {
	public static final int Y = 62;
	public static final int R = 20;
	public static final int[] CELL_X = {-8, -4, 0, 4, 8};
	public static final int CELL_DOOR_Z = -8;
	public static final int CELL_BACK_Z = -16;

	private static final BlockPos MARKER = new BlockPos(0, Y - 1, 0);

	private PrisonBuilder() {
	}

	public static int nearestCellX(int x) {
		int best = CELL_X[0];
		for (int cx : CELL_X) {
			if (Math.abs(cx - x) < Math.abs(best - x)) {
				best = cx;
			}
		}
		return best;
	}

	public static void ensureBuilt(ServerWorld w) {
		if (w.getBlockState(MARKER).isOf(ModBlocks.PRISON_BRICKS)) {
			return;
		}

		BlockState brick = ModBlocks.PRISON_BRICKS.getDefaultState();
		BlockState lamp = ModBlocks.PRISON_LAMP.getDefaultState();
		BlockState bars = ModBlocks.PRISON_BARS.getDefaultState()
				.with(PaneBlock.EAST, true)
				.with(PaneBlock.WEST, true);

		// Пол двора и внешняя стена.
		for (int x = -R; x <= R; x++) {
			for (int z = -R; z <= R; z++) {
				set(w, x, Y, z, brick);
				boolean wall = Math.abs(x) == R || Math.abs(z) == R;
				if (wall) {
					for (int y = Y + 1; y <= Y + 8; y++) {
						set(w, x, y, z, brick);
					}
					if (Math.floorMod(x + z, 5) == 0) {
						set(w, x, Y + 5, z, lamp);
					}
				}
			}
		}

		// Блок камер.
		for (int x = -10; x <= 10; x++) {
			for (int z = CELL_BACK_Z; z <= CELL_DOOR_Z; z++) {
				for (int y = Y + 1; y <= Y + 6; y++) {
					boolean roof = y == Y + 6;
					boolean partition = Math.floorMod(x + 10, 4) == 0;
					if (roof || z == CELL_BACK_Z || partition) {
						set(w, x, y, z, brick);
					} else if (z == CELL_DOOR_Z) {
						set(w, x, y, z, y <= Y + 4 ? bars : brick);
					}
				}
			}
		}

		// Внутри каждой камеры: лампа, койка и расшатанный кирпич.
		for (int cx : CELL_X) {
			set(w, cx, Y + 5, CELL_BACK_Z + 4, lamp);
			set(w, cx + 1, Y + 1, CELL_BACK_Z + 1, Blocks.GRAY_WOOL.getDefaultState());
			set(w, cx, Y + 2, CELL_BACK_Z, ModBlocks.LOOSE_BRICK.getDefaultState());
		}

		// Караулка: x 12..17, z 12..17, вход со стороны двора (x = 12).
		for (int x = 12; x <= 17; x++) {
			for (int z = 12; z <= 17; z++) {
				boolean edge = x == 12 || x == 17 || z == 12 || z == 17;
				for (int y = Y + 1; y <= Y + 5; y++) {
					boolean roof = y == Y + 5;
					if (roof || edge) {
						boolean door = x == 12 && (z == 14 || z == 15) && y <= Y + 2;
						if (!door) {
							set(w, x, y, z, brick);
						}
					}
				}
			}
		}
		set(w, 15, Y + 4, 14, lamp);
		BlockPos chestPos = new BlockPos(16, Y + 1, 15);
		w.setBlockState(chestPos, Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.WEST), Block.NOTIFY_LISTENERS);
		BlockEntity be = w.getBlockEntity(chestPos);
		if (be instanceof ChestBlockEntity chest) {
			chest.setStack(13, new ItemStack(ModItems.PRISON_KEY));
			chest.setStack(12, new ItemStack(Items.BREAD, 4));
		}

		// Ворота 3x3 на южной стене.
		for (int x = -1; x <= 1; x++) {
			for (int y = Y + 1; y <= Y + 3; y++) {
				set(w, x, y, R, ModBlocks.PRISON_GATE.getDefaultState());
			}
		}

		// Маркер «построено» ставим последним.
		w.setBlockState(MARKER, brick, Block.NOTIFY_LISTENERS);
	}

	private static void set(ServerWorld w, int x, int y, int z, BlockState state) {
		w.setBlockState(new BlockPos(x, y, z), state, Block.NOTIFY_LISTENERS);
	}
}
