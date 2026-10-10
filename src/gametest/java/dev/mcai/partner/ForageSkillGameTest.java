package dev.mcai.partner;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;

import java.util.concurrent.atomic.AtomicReference;

/** Proves normal pickup/harvest/melee against server inventories, while chat and Esc remain open. */
final class ForageSkillGameTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    static void run(ClientGameTestContext context, TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
        context.runOnClient(mc -> { PartnerClient.instance().command("disable", ""); mc.gui.setScreen(null); });
        server.runCommand("time set day");
        server.runCommand("gamerule minecraft:spawn_mobs false");
        BlockPos feet = context.computeOnClient(mc -> mc.player.blockPosition());
        BlockPos berry = feet.offset(3, 0, 2);
        AtomicReference<Pig> pig = new AtomicReference<>();
        AtomicReference<ItemEntity> bread = new AtomicReference<>();
        server.runOnServer(mcServer -> {
            var player = connection.getServerPlayer();
            player.closeContainer(); player.setHealth(20); player.getFoodData().setFoodLevel(20);
            player.getInventory().clearContent();
            // Fixture grants a weapon only; the asserted bread/berries/meat must arrive through real gameplay.
            player.getInventory().setItem(0, new ItemStack(Items.STICK));
            player.getInventory().setItem(10, new ItemStack(Items.IRON_SWORD));
            player.getInventory().setSelectedSlot(0); player.inventoryMenu.broadcastChanges();
            for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-10, -1, -10), feet.offset(10, 3, 10)))
                connection.getServerLevel().setBlockAndUpdate(pos, pos.getY() == feet.getY() - 1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
            connection.getServerLevel().setBlockAndUpdate(berry, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3));
            Pig foodAnimal = new Pig(EntityTypes.PIG, connection.getServerLevel());
            foodAnimal.setNoAi(true); foodAnimal.setPersistenceRequired();
            foodAnimal.setPos(feet.getX() + 5.5, feet.getY(), feet.getZ() + .5);
            connection.getServerLevel().addFreshEntity(foodAnimal); pig.set(foodAnimal);
            ItemEntity droppedFood = new ItemEntity(connection.getServerLevel(), feet.getX() + 2.5, feet.getY() + .1, feet.getZ() + .5, new ItemStack(Items.BREAD, 2));
            droppedFood.setNoPickUpDelay(); connection.getServerLevel().addFreshEntity(droppedFood); bread.set(droppedFood);
        });
        connection.waitForClientboundPackets(); context.waitTicks(8);
        ForageSkill skill = context.computeOnClient(mc -> {
            var partner = PartnerClient.instance();
            check(partner.command("enable", "") == 1, "forage fixture activation failed");
            partner.command("auto", "off"); // Reflexes/movement stay active without making LLM requests.
            mc.gui.setScreen(new ChatScreen("KEEP_FORAGE_DRAFT", false));
            return new ForageSkill(mc, partner.motor());
        });
        context.runOnClient(mc -> {
            String start = skill.start();
            check(start.startsWith("STARTED") && start.contains("dropped food"), "forage did not prioritise existing safe dropped food: " + start);
        });
        complete(context, connection, skill);
        server.runOnServer(mcServer -> {
            check(connection.getServerPlayer().getInventory().countItem(Items.BREAD) == 2, "normal pickup never added bread to the server inventory");
            check(pig.get().getHealth() == 10, "food animal was attacked even though reachable safe dropped food existed");
            check(connection.getServerLevel().getBlockState(berry).getValue(SweetBerryBushBlock.AGE) == 3, "berry bush was harvested before existing dropped food");
        });
        context.runOnClient(mc -> {
            check(mc.gui.screen() instanceof ChatScreen, "food pickup closed the chat screen");
            check(mc.gui.screen().children().stream().anyMatch(widget -> widget instanceof EditBox box && box.getValue().equals("KEEP_FORAGE_DRAFT")), "food pickup lost the chat draft");
            mc.gui.setScreen(new PauseScreen(true));
            String start = skill.start();
            check(start.startsWith("STARTED") && start.contains("berry"), "forage did not prefer mature berries before hunting: " + start);
        });
        complete(context, connection, skill);
        server.runOnServer(mcServer -> {
            check(connection.getServerPlayer().getInventory().countItem(Items.SWEET_BERRIES) > 0, "normal berry interaction did not add server inventory food");
            check(connection.getServerLevel().getBlockState(berry).getValue(SweetBerryBushBlock.AGE) == 1, "berry bush was destroyed or normal harvest did not happen");
            check(pig.get().getHealth() == 10, "food animal was attacked despite mature reachable berries");
        });
        context.runOnClient(mc -> {
            check(mc.gui.screen() instanceof PauseScreen, "berry gathering closed the pause overlay");
            mc.gui.setScreen(new ChatScreen("KEEP_HUNT_DRAFT", false));
            String start = skill.start();
            check(start.startsWith("STARTED") && start.contains("hunting"), "animal forage did not start: " + start);
        });
        complete(context, connection, skill);
        server.runOnServer(mcServer -> {
            check(connection.getServerPlayer().getInventory().countItem(Items.PORKCHOP) > 0, "normal melee and pickup never added pork to the server inventory");
            check(!pig.get().isAlive(), "pig remained alive despite reported meat gathering success");
        });
        context.runOnClient(mc -> {
            check(mc.gui.screen() instanceof ChatScreen, "hunting closed the owner's chat screen");
            check(mc.gui.screen().children().stream().anyMatch(widget -> widget instanceof EditBox box && box.getValue().equals("KEEP_HUNT_DRAFT")), "hunting lost the actual chat draft");
            check(mc.player.getInventory().getSelectedSlot() == 0, "hunting did not restore the owner's held-item selection");
            mc.gui.setScreen(null); PartnerClient.instance().command("disable", "");
        });
        server.runOnServer(mcServer -> { if (pig.get().isAlive()) pig.get().discard(); if (bread.get().isAlive()) bread.get().discard(); connection.getServerLevel().setBlockAndUpdate(berry, Blocks.AIR.defaultBlockState()); });
        connection.waitForClientboundPackets();
    }

    private static void complete(ClientGameTestContext context, TestDedicatedServerConnection connection, ForageSkill skill) {
        for (int tick = 0; tick < 420 && context.computeOnClient(mc -> skill.busy()); tick++) {
            context.waitTicks(1); context.runOnClient(mc -> skill.tick());
        }
        connection.waitForServerboundPackets(); connection.waitForClientboundPackets();
        context.runOnClient(mc -> {
            check(!skill.busy(), "forage remained busy past the 400 tick limit");
            String result = skill.drainResult(); check(result != null && result.startsWith("OK:"), "food gathering failed: " + result);
        });
    }
}
