package com.prisonrealm.item;

import com.prisonrealm.prison.PrisonManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;

/**
 * Запечатывающий куб. Использование на игроке: перед целью появляется куб, из него
 * тянутся щупальца и 4 секунды удерживают её, затем куб поглощает цель и отправляет
 * в тюремное измерение на 10 минут. Куб висит на месте ареста, пока длится срок.
 */
public class SealingCubeItem extends Item {
	public static final int COOLDOWN_TICKS = 20 * 20;

	public SealingCubeItem(Settings settings) {
		super(settings);
	}

	@Override
	public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
		if (user.getWorld().isClient) {
			return ActionResult.SUCCESS;
		}
		if (!(entity instanceof ServerPlayerEntity target) || !(user instanceof ServerPlayerEntity owner)) {
			return ActionResult.PASS;
		}
		if (PrisonManager.isPrisoner(owner) || PrisonManager.isPrisoner(target)) {
			owner.sendMessage(Text.translatable("prisonrealm.cannot_seal"), true);
			return ActionResult.FAIL;
		}
		if (PrisonManager.beginSeal(target, owner)) {
			owner.getItemCooldownManager().set(this, COOLDOWN_TICKS);
			owner.sendMessage(Text.translatable("prisonrealm.sealing", target.getDisplayName()), true);
			return ActionResult.SUCCESS;
		}
		return ActionResult.FAIL;
	}
}
