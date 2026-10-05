package dev.mcai.partner;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.atomic.AtomicReference;

/** Real server damage proves that self-defence is a physical action, independent of model planning. */
final class CombatReflexGameTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    static void run(ClientGameTestContext context, TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
        context.runOnClient(mc -> PartnerClient.instance().command("disable", ""));
        server.runCommand("time set midnight");
        server.runCommand("gamerule minecraft:spawn_mobs false");
        AtomicReference<Zombie> target = new AtomicReference<>();
        server.runOnServer(mcServer -> {
            var player = connection.getServerPlayer();
            player.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
            player.getInventory().setItem(1, new ItemStack(Items.STONE_PICKAXE));
            player.inventoryMenu.broadcastChanges();
            Zombie zombie = new Zombie(connection.getServerLevel());
            zombie.setNoAi(true); zombie.setPersistenceRequired();
            zombie.setPos(player.getX() + 1.7, player.getY(), player.getZ());
            connection.getServerLevel().addFreshEntity(zombie); target.set(zombie);
        });
        connection.waitForClientboundPackets(); context.waitTicks(12);
        server.runOnServer(mcServer -> check(target.get().getHealth() == 20, "disabled mod attacked a nearby zombie"));
        server.runOnServer(mcServer -> target.get().discard());
        connection.waitForClientboundPackets();
        BlockPos log = context.computeOnClient(mc -> mc.player.blockPosition().offset(0, 0, 1));
        server.runOnServer(mcServer -> connection.getServerLevel().setBlockAndUpdate(log, Blocks.OAK_LOG.defaultBlockState()));
        connection.waitForClientboundPackets();
        context.runOnClient(mc -> {
            var partner = PartnerClient.instance();
            check(partner.command("enable", "") == 1, "combat fixture activation failed");
            partner.command("auto", "off"); // No LLM calls; self-defence remains available while takeover is enabled.
            mc.player.getInventory().setSelectedSlot(1);
            check(partner.motor().dig(log).startsWith("STARTED"), "combat fixture mining did not start");
        });
        server.runOnServer(mcServer -> {
            var player = connection.getServerPlayer();
            Zombie zombie = new Zombie(connection.getServerLevel());
            zombie.setNoAi(true); zombie.setPersistenceRequired();
            zombie.setPos(player.getX() + 1.7, player.getY(), player.getZ());
            connection.getServerLevel().addFreshEntity(zombie); target.set(zombie);
        });
        connection.waitForClientboundPackets();
        context.waitFor(mc -> PartnerClient.instance().motor().defending(), 12);
        context.runOnClient(mc -> {
            check(PartnerClient.instance().motor().status().contains("fighting"), "reflex did not interrupt mining to fight");
            check(mc.player.getInventory().getSelectedSlot() == 0, "reflex did not select the stronger hotbar weapon");
        });
        context.waitTicks(35); connection.waitForServerboundPackets();
        server.runOnServer(mcServer -> check(target.get().getHealth() < 20, "server never received a real reflex attack"));
        server.runOnServer(mcServer -> target.get().discard());
        connection.waitForClientboundPackets();
        context.waitFor(mc -> !PartnerClient.instance().motor().defending(), 50);
        context.runOnClient(mc -> check(mc.player.getInventory().getSelectedSlot() == 1, "combat did not restore the previous hotbar selection"));

        // A nearby creeper should trigger a retreat rather than repeated melee attacks.
        AtomicReference<Creeper> creeper = new AtomicReference<>();
        server.runOnServer(mcServer -> {
            var player = connection.getServerPlayer();
            Creeper mob = new Creeper(EntityTypes.CREEPER, connection.getServerLevel());
            mob.setNoAi(true); mob.setPersistenceRequired();
            mob.setPos(player.getX() + 2.3, player.getY(), player.getZ());
            connection.getServerLevel().addFreshEntity(mob); creeper.set(mob);
        });
        connection.waitForClientboundPackets();
        context.waitFor(mc -> PartnerClient.instance().motor().status().equals("defending: retreating"), 12);
        context.waitTicks(12); connection.waitForServerboundPackets();
        server.runOnServer(mcServer -> check(creeper.get().getHealth() == 20, "creeper reflex attacked instead of retreating"));
        context.runOnClient(mc -> PartnerClient.instance().command("disable", ""));
        context.runOnClient(mc -> check(!PartnerClient.instance().motor().defending(), "pause did not stop self-defence"));
        server.runOnServer(mcServer -> { creeper.get().discard(); connection.getServerLevel().setBlockAndUpdate(log, Blocks.AIR.defaultBlockState()); });
        connection.waitForClientboundPackets();
    }
}
