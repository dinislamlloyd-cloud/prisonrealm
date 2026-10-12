package com.prisonrealm.block;

import com.prisonrealm.ModItems;
import com.prisonrealm.prison.PrisonManager;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Главные ворота тюрьмы. С ключом заключённый совершает побег. */
public class PrisonGateBlock extends Block {
	public PrisonGateBlock(AbstractBlock.Settings settings) {
		super(settings);
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
		if (world.isClient) {
			return ActionResult.SUCCESS;
		}
		if (!(player instanceof ServerPlayerEntity sp) || !PrisonManager.isPrisoner(sp)) {
			return ActionResult.PASS;
		}
		ItemStack held = sp.getStackInHand(hand);
		if (!held.isOf(ModItems.PRISON_KEY)) {
			sp.sendMessage(Text.translatable("prisonrealm.gate_needs_key"), true);
			return ActionResult.FAIL;
		}
		if (!sp.isCreative()) {
			held.decrement(1);
		}
		PrisonManager.release(sp, true);
		return ActionResult.SUCCESS;
	}
}
