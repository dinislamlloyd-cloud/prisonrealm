package com.prisonrealm.block;

import com.prisonrealm.ModBlocks;
import com.prisonrealm.ModItems;
import com.prisonrealm.prison.PrisonManager;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
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

/** Расшатанный кирпич: заключённый вынимает его и находит спрятанный ключ от камеры. */
public class LooseBrickBlock extends Block {
	public LooseBrickBlock(AbstractBlock.Settings settings) {
		super(settings);
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
		if (world.isClient) {
			return ActionResult.SUCCESS;
		}
		if (player instanceof ServerPlayerEntity sp && PrisonManager.isPrisoner(sp)) {
			sp.getInventory().offerOrDrop(new ItemStack(ModItems.PRISON_KEY));
			world.setBlockState(pos, ModBlocks.PRISON_BRICKS.getDefaultState(), 3);
			world.playSound(null, pos, SoundEvents.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 1.0f, 0.7f);
			sp.sendMessage(Text.translatable("prisonrealm.found_key"), true);
			return ActionResult.SUCCESS;
		}
		return ActionResult.PASS;
	}
}
