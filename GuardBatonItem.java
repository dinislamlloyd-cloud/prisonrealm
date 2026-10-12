package com.prisonrealm.item;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolMaterials;

/** Дубинка стражника: слабый урон, но замедляет цель, чтобы можно было надеть наручники. */
public class GuardBatonItem extends SwordItem {
	public GuardBatonItem(Settings settings) {
		super(ToolMaterials.WOOD, 1, -2.2f, settings);
	}

	@Override
	public boolean postHit(ItemStack stack, LivingEntity target, LivingEntity attacker) {
		target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 2), attacker);
		return super.postHit(stack, target, attacker);
	}
}
