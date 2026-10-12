package com.prisonrealm.block;

import com.prisonrealm.ModItems;
import com.prisonrealm.prison.PrisonBuilder;
import com.prisonrealm.prison.PrisonManager;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PaneBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Решётка камеры. Заключённый с ключом может открыть дверь своей камеры. */
public class PrisonBarsBlock extends PaneBlock {
	public PrisonBarsBlock(AbstractBlock.Settings settings) {
		super(settings);
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
		ItemStack held = player.getStackInHand(hand);
		if (world.isClient || !held.isOf(ModItems.PRISON_KEY) || !(player instanceof ServerPlayerEntity sp)) {
			return ActionResult.PASS;
		}
		if (!PrisonManager.isPrisoner(sp)) {
			return ActionResult.PASS;
		}
		if (pos.getZ() != PrisonBuilder.CELL_DOOR_Z) {
			sp.sendMessage(Text.translatable("prisonrealm.key_does_not_fit"), true);
			return ActionResult.FAIL;
		}

		int cx = PrisonBuilder.nearestCellX(pos.getX());
		for (int dx = -1; dx <= 1; dx++) {
			for (int y = PrisonBuilder.Y + 1; y <= PrisonBuilder.Y + 4; y++) {
				BlockPos p = new BlockPos(cx + dx, y, PrisonBuilder.CELL_DOOR_Z);
				if (world.getBlockState(p).isOf(this)) {
					world.setBlockState(p, Blocks.AIR.getDefaultState(), 3);
				}
			}
		}
		world.playSound(null, pos, SoundEvents.BLOCK_IRON_DOOR_OPEN, SoundCategory.BLOCKS, 1.0f, 0.8f);
		if (!sp.isCreative()) {
			held.decrement(1);
		}
		sp.sendMessage(Text.translatable("prisonrealm.cell_opened"), true);
		return ActionResult.SUCCESS;
	}
}
