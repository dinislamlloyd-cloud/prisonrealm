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
 * Запечатывающий куб. ПКМ по игроку: куб хватает цель руками, вокруг неё зависают
 * красные кубы. Скажите «close» в чат, и кубы смыкаются на цели. Можно и сразу:
 * держите куб в руке, смотрите на игрока и скажите «close».
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
			// Подсказку «скажите close» показывает сам beginSeal.
			owner.getItemCooldownManager().set(this, COOLDOWN_TICKS);
			return ActionResult.SUCCESS;
		}
		return ActionResult.FAIL;
	}
}
