package com.prisonrealm;

import com.prisonrealm.prison.PrisonCommands;
import com.prisonrealm.prison.PrisonManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PrisonRealmMod implements ModInitializer {
	public static final String MOD_ID = "prisonrealm";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final RegistryKey<World> PRISON_WORLD =
			RegistryKey.of(RegistryKeys.WORLD, new Identifier(MOD_ID, "prison"));

	@Override
	public void onInitialize() {
		ModBlocks.register();
		ModItems.register();

		Registry.register(Registries.ITEM_GROUP, new Identifier(MOD_ID, "main"),
				FabricItemGroup.builder()
						.icon(() -> new ItemStack(ModItems.SEALING_CUBE))
						.displayName(Text.translatable("itemGroup.prisonrealm.main"))
						.entries((context, entries) -> {
							entries.add(ModItems.SEALING_CUBE);
							entries.add(ModItems.HANDCUFFS);
							entries.add(ModItems.GUARD_BATON);
							entries.add(ModItems.PRISON_KEY);
							entries.add(ModBlocks.PRISON_BRICKS);
							entries.add(ModBlocks.PRISON_BARS);
							entries.add(ModBlocks.PRISON_LAMP);
							entries.add(ModBlocks.LOOSE_BRICK);
							entries.add(ModBlocks.PRISON_GATE);
						})
						.build());

		PrisonCommands.register();

		ServerTickEvents.END_SERVER_TICK.register(PrisonManager::tick);

		// Слова «close» и «open» в чате управляют кубом.
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
				PrisonManager.onChat(sender, message.getContent().getString()));

		// Заключённые не могут драться.
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
				PrisonManager.isPrisoner(player) ? ActionResult.FAIL : ActionResult.PASS);

		// Если заключённый умер, он возрождается в своей камере.
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
				PrisonManager.onRespawn(newPlayer));

		LOGGER.info("Prison Realm loaded");
	}
}
