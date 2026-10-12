package com.prisonrealm;

import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import org.joml.Vector3f;

import java.util.List;

/**
 * Крафт на земле: выбросите (Q) 3 обсидиана и 3 красных красителя так,
 * чтобы они лежали рядом, и они сольются в Запечатывающий куб.
 */
public final class GroundCrafting {
	private static final int OBSIDIAN_NEEDED = 3;
	private static final int DYE_NEEDED = 3;
	/** Насколько близко должны лежать предметы друг к другу. */
	private static final double RADIUS = 1.5;
	/** Ищем выброшенные предметы рядом с игроками. */
	private static final double SEARCH = 24.0;

	private static final DustParticleEffect RED_DUST =
			new DustParticleEffect(new Vector3f(0.8f, 0.03f, 0.03f), 1.5f);

	private GroundCrafting() {
	}

	public static void tick(MinecraftServer server) {
		if (server.getTicks() % 10 != 0) {
			return;
		}
		for (ServerWorld w : server.getWorlds()) {
			for (ServerPlayerEntity p : w.getPlayers()) {
				List<ItemEntity> obsidian = w.getEntitiesByClass(ItemEntity.class,
						p.getBoundingBox().expand(SEARCH), e -> e.isAlive() && e.isOnGround() && e.getStack().isOf(Items.OBSIDIAN));
				for (ItemEntity anchor : obsidian) {
					if (anchor.isAlive()) {
						tryCraft(w, anchor);
					}
				}
			}
		}
	}

	private static void tryCraft(ServerWorld w, ItemEntity anchor) {
		List<ItemEntity> near = w.getEntitiesByClass(ItemEntity.class,
				anchor.getBoundingBox().expand(RADIUS), e -> e.isAlive() && e.isOnGround());
		if (count(near, Items.OBSIDIAN) < OBSIDIAN_NEEDED || count(near, Items.RED_DYE) < DYE_NEEDED) {
			return;
		}
		consume(near, Items.OBSIDIAN, OBSIDIAN_NEEDED);
		consume(near, Items.RED_DYE, DYE_NEEDED);

		double x = anchor.getX();
		double y = anchor.getY() + 0.3;
		double z = anchor.getZ();
		ItemEntity result = new ItemEntity(w, x, y, z, new ItemStack(ModItems.SEALING_CUBE), 0.0, 0.25, 0.0);
		result.setPickupDelay(10);
		w.spawnEntity(result);

		w.spawnParticles(RED_DUST, x, y + 0.3, z, 40, 0.4, 0.4, 0.4, 0.0);
		w.spawnParticles(ParticleTypes.FLASH, x, y + 0.3, z, 1, 0, 0, 0, 0);
		w.playSound(null, anchor.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.BLOCKS, 1.5f, 0.7f);
		w.playSound(null, anchor.getBlockPos(), SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.BLOCKS, 1.0f, 0.6f);
	}

	private static int count(List<ItemEntity> items, Item item) {
		int n = 0;
		for (ItemEntity e : items) {
			if (e.isAlive() && e.getStack().isOf(item)) {
				n += e.getStack().getCount();
			}
		}
		return n;
	}

	private static void consume(List<ItemEntity> items, Item item, int amount) {
		for (ItemEntity e : items) {
			if (amount <= 0) {
				return;
			}
			if (!e.isAlive() || !e.getStack().isOf(item)) {
				continue;
			}
			ItemStack stack = e.getStack().copy();
			int take = Math.min(amount, stack.getCount());
			stack.decrement(take);
			amount -= take;
			if (stack.isEmpty()) {
				e.discard();
			} else {
				e.setStack(stack);
			}
		}
	}
}
