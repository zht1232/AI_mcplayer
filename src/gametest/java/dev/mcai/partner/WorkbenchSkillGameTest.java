package dev.mcai.partner;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Tests the real vanilla recipe and placement packets, never client-predicted output alone. */
final class WorkbenchSkillGameTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    static void run(ClientGameTestContext context, TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
        context.runOnClient(mc -> { PartnerClient.instance().command("disable", ""); mc.gui.setScreen(null); });
        BlockPos feet = context.computeOnClient(mc -> mc.player.blockPosition());
        server.runOnServer(mcServer -> {
            var player = connection.getServerPlayer();
            player.closeContainer(); player.getInventory().clearContent();
            // Fixture gifts ingredients only; none of the asserted tools or world workbenches exist yet.
            player.getInventory().setItem(0, new ItemStack(Items.BREAD, 2));
            player.getInventory().setItem(10, new ItemStack(Items.OAK_PLANKS, 16));
            player.getInventory().setItem(11, new ItemStack(Items.STICK, 8));
            player.getInventory().setItem(12, new ItemStack(Items.CRAFTING_TABLE));
            player.getInventory().setSelectedSlot(0); player.inventoryMenu.broadcastChanges();
            for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-4, -1, -4), feet.offset(4, 2, 4)))
                connection.getServerLevel().setBlockAndUpdate(pos, pos.getY() == feet.getY() - 1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
        });
        connection.waitForClientboundPackets(); context.waitTicks(8);
        WorkbenchSkill skill = context.computeOnClient(mc -> {
            ClientMotor motor = new ClientMotor(mc, 32); motor.active(true);
            return new WorkbenchSkill(mc, motor);
        });
        context.runOnClient(mc -> {
            mc.player.getInventory().setSelectedSlot(0);
            mc.gui.setScreen(new ChatScreen("KEEP_WORKBENCH_DRAFT", false));
            check(skill.start("wooden_axe").startsWith("STARTED"), "workbench placement task did not start");
        });
        complete(context, skill);
        connection.waitForServerboundPackets(); connection.waitForClientboundPackets();
        server.runOnServer(mcServer -> {
            var player = connection.getServerPlayer();
            check(player.getInventory().countItem(Items.WOODEN_AXE) == 1, "server never received a crafted wooden axe");
            check(player.getInventory().countItem(Items.OAK_PLANKS) == 13 && player.getInventory().countItem(Items.STICK) == 6, "axe recipe did not consume exact existing ingredients");
            check(player.getInventory().countItem(Items.CRAFTING_TABLE) == 0, "workbench placement did not consume the existing table");
            check(tables(connection, feet) == 1, "server did not confirm exactly one placed workbench");
            check(player.containerMenu == player.inventoryMenu && player.inventoryMenu.getCarried().isEmpty(), "own server workbench was not closed cleanly");
        });
        context.runOnClient(mc -> {
            check(mc.gui.screen() instanceof ChatScreen, "workbench crafting lost the owner's chat screen");
            check(mc.gui.screen().children().stream().anyMatch(widget -> widget instanceof EditBox box && box.getValue().equals("KEEP_WORKBENCH_DRAFT")), "workbench crafting lost the actual chat draft");
            check(mc.player.getInventory().getSelectedSlot() == 0 && mc.player.getInventory().getItem(0).is(Items.BREAD), "placement altered the owner's held-item selection");
            mc.gui.setScreen(new PauseScreen(true));
            check(skill.start("wooden_sword").startsWith("STARTED"), "existing workbench was not reused for the sword");
        });
        complete(context, skill);
        connection.waitForServerboundPackets(); connection.waitForClientboundPackets();
        server.runOnServer(mcServer -> {
            check(connection.getServerPlayer().getInventory().countItem(Items.WOODEN_SWORD) == 1, "server never received a crafted wooden sword");
            check(tables(connection, feet) == 1, "reuse placed an unnecessary second table");
        });
        context.runOnClient(mc -> {
            check(mc.gui.screen() instanceof PauseScreen, "workbench crafting closed the owner's pause overlay");
            check(skill.start("wooden_pickaxe").startsWith("STARTED"), "pickaxe recipe did not start");
        });
        complete(context, skill);
        connection.waitForServerboundPackets(); connection.waitForClientboundPackets();
        server.runOnServer(mcServer -> {
            var player = connection.getServerPlayer();
            check(player.getInventory().countItem(Items.WOODEN_PICKAXE) == 1, "server never received a crafted wooden pickaxe");
            check(player.getInventory().countItem(Items.OAK_PLANKS) == 8 && player.getInventory().countItem(Items.STICK) == 3, "tool recipes consumed incorrect ingredient amounts");
        });

        // Cancel while ingredients are on the cursor and in the grid; they must not be dropped.
        context.runOnClient(mc -> check(skill.start("wooden_pickaxe").startsWith("STARTED"), "cancellation recipe did not start"));
        boolean gridFilled = false;
        for (int tick = 0; tick < 200 && !gridFilled; tick++) {
            context.waitTicks(1); context.runOnClient(mc -> skill.tick());
            gridFilled = context.computeOnClient(mc -> mc.player.containerMenu instanceof CraftingMenu menu && !menu.getSlot(1).getItem().isEmpty() && !menu.getCarried().isEmpty());
        }
        check(gridFilled, "cancellation fixture never reached a nonempty workbench cursor/grid");
        connection.waitForServerboundPackets(); connection.waitForClientboundPackets();
        context.runOnClient(mc -> { skill.cancel(); check(!skill.busy() && skill.drainResult().startsWith("FAILED"), "cancel did not stop workbench crafting"); });
        connection.waitForServerboundPackets(); connection.waitForClientboundPackets(); context.waitTicks(8);
        server.runOnServer(mcServer -> {
            var player = connection.getServerPlayer();
            check(player.getInventory().countItem(Items.OAK_PLANKS) == 8 && player.getInventory().countItem(Items.STICK) == 3, "cancellation lost or dropped workbench ingredients");
            check(player.containerMenu == player.inventoryMenu && player.inventoryMenu.getCarried().isEmpty(), "cancel left the server cursor/menu dirty");
            check(player.getInventory().countItem(Items.WOODEN_PICKAXE) == 1, "cancel accidentally completed the second tool");
        });
        context.runOnClient(mc -> { check(mc.gui.screen() instanceof PauseScreen, "cancel lost the owner's pause overlay"); mc.gui.setScreen(null); });
    }
    private static long tables(TestDedicatedServerConnection connection, BlockPos feet) {
        long count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-4, -1, -4), feet.offset(4, 2, 4))) if (connection.getServerLevel().getBlockState(pos).is(Blocks.CRAFTING_TABLE)) count++;
        return count;
    }
    private static void complete(ClientGameTestContext context, WorkbenchSkill skill) {
        for (int tick = 0; tick < 420 && context.computeOnClient(mc -> skill.busy()); tick++) { context.waitTicks(1); context.runOnClient(mc -> skill.tick()); }
        context.runOnClient(mc -> {
            check(!skill.busy(), "workbench skill remained busy past its bounded timeout");
            String result = skill.drainResult(); check(result != null && result.startsWith("OK:"), "workbench recipe failed: " + result);
        });
    }
}
