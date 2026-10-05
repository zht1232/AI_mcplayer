package dev.mcai.partner;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Comparator;

/** A bounded approach -> confirmed mining -> pickup sequence, independent of model narration. */
public final class HarvestSkill {
    private enum Phase { APPROACH, MINE, WAIT_DROPS, PICKUP }
    private final Minecraft mc;
    private final ClientMotor motor;
    private BlockPos target;
    private Phase phase;
    private boolean mined;
    private int age;
    private int dropWait;
    private int initialCount;
    private String dropItem;
    private String outcome;
    private final java.util.Map<BlockPos, Long> retryAfter = new java.util.HashMap<>();
    public HarvestSkill(Minecraft mc, ClientMotor motor) { this.mc = mc; this.motor = motor; }
    public boolean busy() { return target != null; }
    public void stop() { target = null; phase = null; mined = false; age = 0; }
    public String start(BlockPos block) {
        if (retryAfter.getOrDefault(block, 0L) > System.currentTimeMillis()) return "FAILED: this target recently failed; choose another or check the server interaction rule";
        if (busy() || motor.busy()) return "FAILED: another physical skill is running";
        if (mc.player == null || mc.level == null || ScreenPolicy.blocksWorld(mc.gui.screen())) return "FAILED: world controls are unavailable";
        if (!mc.level.hasChunkAt(block) || mc.level.getBlockState(block).isAir()
                || mc.level.getBlockState(block).getDestroySpeed(mc.level, block) < 0) return "FAILED: target is not a loaded mineable block";
        outcome = null; mined = false; age = 0; dropWait = 0;
        String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(block).getBlock()).toString();
        dropItem = blockId.equals("minecraft:carrots") ? "minecraft:carrot" : blockId.equals("minecraft:potatoes") ? "minecraft:potato" : blockId;
        initialCount = motor.inventoryCount(dropItem);
        if (reachable(block)) {
            String digging = motor.dig(block);
            if (digging.startsWith("STARTED")) { target = block.immutable(); phase = Phase.MINE; }
            return digging;
        }
        ArrayList<BlockPos> candidates = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int dy = -4; dy <= 1; dy++) candidates.add(block.relative(direction).offset(0, dy, 0));
            candidates.add(new BlockPos(block.getX() + direction.getStepX(), mc.player.blockPosition().getY(), block.getZ() + direction.getStepZ()));
        }
        candidates.sort(Comparator.comparingDouble(pos -> mc.player.distanceToSqr(Vec3.atCenterOf(pos))));
        for (BlockPos nearby : candidates) {
            String walking = motor.moveTo(nearby);
            if (walking.startsWith("STARTED")) { target = block.immutable(); phase = Phase.APPROACH; return "STARTED: approaching observed block, then mining and collecting"; }
        }
        return "FAILED: no safe adjacent path to the observed block";
    }
    private boolean reachable(BlockPos block) {
        return mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(block)) <= mc.player.blockInteractionRange();
    }
    public void feedback(String result) {
        if (!busy()) return;
        if (result.startsWith("OK: server confirmed block state change")) mined = true;
        if (result.startsWith("FAILED:")) finish(result);
    }
    public void tick() {
        if (!busy()) return;
        if (mc.player == null || mc.level == null || !mc.player.isAlive()) { finish("FAILED: harvesting interrupted by death/disconnect"); return; }
        if (ScreenPolicy.blocksWorld(mc.gui.screen())) { motor.stop(); finish("FAILED: harvesting interrupted by interactive UI"); return; }
        if (++age > 700) { motor.stop(); finish("FAILED: harvest skill timed out"); return; }
        if (motor.busy()) return;
        switch (phase) {
            case APPROACH -> {
                String result = motor.dig(target);
                if (result.startsWith("STARTED")) phase = Phase.MINE;
                else finish(result);
            }
            case MINE -> {
                if (!mined) { finish("FAILED: mining ended without authoritative server confirmation"); return; }
                phase = Phase.WAIT_DROPS; dropWait = 0;
            }
            case WAIT_DROPS -> {
                if (motor.inventoryCount(dropItem) > initialCount) { finish("OK: harvest inventory increased; no inventory screen was opened"); return; }
                if (++dropWait < 10) return; // Entity spawn/inventory packets can follow the block update.
                String pickup = motor.collect();
                if (pickup.startsWith("STARTED")) phase = Phase.PICKUP;
                else if (dropWait >= 60) finish("MINED: server confirmed the block change but no drop/pickup arrived; read passive inventory counts");
            }
            case PICKUP -> finish("OK: harvest sequence ended; read passive inventory counts, no screen opening is needed");
        }
    }
    private void finish(String result) {
        if (result.startsWith("FAILED") && target != null) {
            retryAfter.put(target, System.currentTimeMillis() + 60_000);
            if (retryAfter.size() > 64) retryAfter.remove(retryAfter.keySet().iterator().next());
        }
        outcome = result; stop();
    }
    public String drainOutcome() { String value = outcome; outcome = null; return value; }
}
