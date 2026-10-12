package com.prisonrealm.item;

import com.prisonrealm.prison.PrisonManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;

/**
 * Наручники. Надеть можно на игрока, которого замедлили дубинкой,
 * либо на сильно раненого (до 3 сердец).
 */
public class HandcuffsItem extends Item {
	public HandcuffsItem(Settings settings) {
		super(settings);
	}

	@Override
	public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
		if (user.getWorld().isClient) {
			return ActionResult.SUCCESS;
		}
		if (!(entity instanceof ServerPlayerEntity target) || !(user instanceof ServerPlayerEntity guard)) {
			return ActionResult.PASS;
		}
		if (PrisonManager.isPrisoner(guard)) {
			return ActionResult.FAIL;
		}
		if (PrisonManager.isPrisoner(target)) {
			guard.sendMessage(Text.translatable("prisonrealm.already_prisoner"), true);
			return ActionResult.FAIL;
		}
		if (!target.hasStatusEffect(StatusEffects.SLOWNESS) && target.getHealth() > 6.0f) {
			guard.sendMessage(Text.translatable("prisonrealm.target_resists"), true);
			return ActionResult.FAIL;
		}
		if (PrisonManager.arrest(target, PrisonManager.DEFAULT_SECONDS)) {
			guard.server.getPlayerManager().broadcast(
					Text.translatable("prisonrealm.arrested_by", target.getDisplayName(), guard.getDisplayName()), false);
			stack.damage(1, guard, p -> p.sendToolBreakStatus(hand));
			return ActionResult.SUCCESS;
		}
		return ActionResult.FAIL;
	}
}
