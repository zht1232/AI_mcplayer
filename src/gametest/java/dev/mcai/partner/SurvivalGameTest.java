package dev.mcai.partner;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

final class SurvivalGameTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void run(ClientGameTestContext context, TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
        context.runOnClient(mc -> PartnerClient.instance().command("disable", ""));
        BlockPos origin = context.computeOnClient(mc -> BlockPos.containing(mc.player.getX(), Math.floor(mc.player.getY() + .1), mc.player.getZ()));
        ArrayList<BlockPos> crops = new ArrayList<>(), immature = new ArrayList<>();
        for (int x = 2; x <= 9; x++) for (int z = -3; z <= 4; z++) {
            BlockPos pos = origin.offset(x, 0, z);
            if (z == -3) immature.add(pos); else crops.add(pos);
        }
        AtomicReference<Display.ItemDisplay> display = new AtomicReference<>();
        server.runOnServer(mcServer -> {
            for (BlockPos crop : crops) {
                connection.getServerLevel().setBlockAndUpdate(crop.below(), Blocks.FARMLAND.defaultBlockState());
                connection.getServerLevel().setBlockAndUpdate(crop, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
            }
            for (BlockPos crop : immature) {
                connection.getServerLevel().setBlockAndUpdate(crop.below(), Blocks.FARMLAND.defaultBlockState());
                connection.getServerLevel().setBlockAndUpdate(crop, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 0));
            }
            var proxy = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, connection.getServerLevel());
            proxy.setPos(crops.getFirst().getX() + .5, crops.getFirst().getY() + .3, crops.getFirst().getZ() + .5);
            ((dev.mcai.partner.mixin.motor.DisplayItemAccessor)proxy).wildling$setItem(new ItemStack(Items.WHEAT));
            connection.getServerLevel().addFreshEntity(proxy); display.set(proxy);
        });
        connection.waitForClientboundPackets();
        int before = context.computeOnClient(mc -> PartnerClient.instance().motor().inventoryCount("minecraft:wheat"));
        context.runOnClient(mc -> {
            var partner = PartnerClient.instance(); partner.command("enable", "");
            check(partner.motor().snapshot(8).getAsJsonArray("customCropTargets").toString().contains("minecraft:wheat"), "wheat display lost its item/backing crop identity");
            partner.takeLocalControl("收小麦16个");
            mc.gui.setScreen(new net.minecraft.client.gui.screens.ChatScreen("KEEP_DRAFT", false));
        });
        server.runOnServer(mcServer -> connection.getServerPlayer().sendSystemMessage(net.minecraft.network.chat.Component.literal("Guest: @" + connection.getServerPlayer().getGameProfile().name() + " 收8个小麦")));
        connection.waitForClientboundPackets();
        context.waitFor(mc -> PartnerClient.instance().motor().inventoryCount("minecraft:wheat") >= before + 24 && !PartnerClient.instance().collectionActive(), 1800);
        context.runOnClient(mc -> check(mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen, "continuous crop collection replaced the chat overlay with inventory"));
        connection.waitForServerboundPackets();
        server.runOnServer(mcServer -> {
            check(crops.stream().filter(pos -> connection.getServerLevel().getBlockState(pos).isAir()).count() >= 24, "continuous crop quotas were only predicted by the client");
            check(immature.stream().allMatch(pos -> connection.getServerLevel().getBlockState(pos).is(Blocks.WHEAT)), "immature crops were destroyed by the collection task");
        });
        context.runOnClient(mc -> { PartnerClient.instance().command("disable", ""); mc.gui.setScreen(null); });
        server.runOnServer(mcServer -> display.get().discard());

        // Hunger/health are set by the test server; recovery must come from real food consumption.
        server.runOnServer(mcServer -> {
            var player = connection.getServerPlayer();
            player.setHealth(8); player.getFoodData().setFoodLevel(10);
            player.getInventory().setItem(10, new ItemStack(Items.COOKED_BEEF, 4)); player.inventoryMenu.broadcastChanges();
        });
        connection.waitForClientboundPackets();
        context.waitFor(mc -> mc.player.getHealth() <= 8.1f && mc.player.getFoodData().getFoodLevel() <= 10, 50);
        context.runOnClient(mc -> {
            var partner = PartnerClient.instance(); partner.command("enable", ""); partner.command("auto", "off");
            mc.gui.setScreen(new net.minecraft.client.gui.screens.ChatScreen("KEEP_DRAFT", false));
        });
        context.waitFor(mc -> mc.player.getFoodData().getFoodLevel() >= 18 && mc.player.getHealth() > 8, 300);
        connection.waitForServerboundPackets();
        server.runOnServer(mcServer -> check(connection.getServerPlayer().getHealth() > 8, "server health never recovered from food"));
        context.runOnClient(mc -> { check(mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen, "food rescue opened inventory or closed chat"); PartnerClient.instance().command("disable", ""); mc.gui.setScreen(null); });

        server.runOnServer(mcServer -> {
            var player = connection.getServerPlayer(); player.setHealth(8); player.getFoodData().setFoodLevel(20); player.getFoodData().setSaturation(0);
            ItemStack potion = new ItemStack(Items.POTION);
            potion.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS, new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.HEALING));
            player.getInventory().setItem(20, potion); player.inventoryMenu.broadcastChanges();
        });
        connection.waitForClientboundPackets(); context.waitFor(mc -> mc.player.getHealth() <= 8.1f, 50);
        context.runOnClient(mc -> { var partner = PartnerClient.instance(); partner.command("enable", ""); partner.command("auto", "off"); mc.gui.setScreen(new net.minecraft.client.gui.screens.ChatScreen("KEEP_POTION_DRAFT", false)); });
        context.waitFor(mc -> PartnerClient.instance().motor().inventoryCount("minecraft:potion") == 0 && mc.player.getHealth() >= 12, 200);
        connection.waitForServerboundPackets();
        server.runOnServer(mcServer -> check(connection.getServerPlayer().getHealth() >= 12, "healing potion did not improve server health"));
        context.runOnClient(mc -> { check(mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen, "potion rescue changed the draft screen"); PartnerClient.instance().command("disable", ""); mc.gui.setScreen(null); });

        BlockPos cage = context.computeOnClient(mc -> BlockPos.containing(mc.player.getX(), Math.floor(mc.player.getY() + .1), mc.player.getZ()));
        server.runOnServer(mcServer -> {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                connection.getServerLevel().setBlockAndUpdate(cage.relative(side), Blocks.DIRT.defaultBlockState());
                connection.getServerLevel().setBlockAndUpdate(cage.relative(side).above(), Blocks.DIRT.defaultBlockState());
            }
        });
        connection.waitForClientboundPackets();
        context.runOnClient(mc -> { var partner = PartnerClient.instance(); partner.command("enable", ""); partner.command("auto", "off"); });
        context.waitFor(mc -> Math.abs(mc.player.getX() - (cage.getX() + .5)) > 1.5 || Math.abs(mc.player.getZ() - (cage.getZ() + .5)) > 1.5, 300);
        connection.waitForServerboundPackets();
        server.runOnServer(mcServer -> check(Math.abs(connection.getServerPlayer().getX() - (cage.getX() + .5)) > 1.5 || Math.abs(connection.getServerPlayer().getZ() - (cage.getZ() + .5)) > 1.5, "server did not accept escaping the enclosure"));
        context.runOnClient(mc -> PartnerClient.instance().command("disable", ""));
    }
}
