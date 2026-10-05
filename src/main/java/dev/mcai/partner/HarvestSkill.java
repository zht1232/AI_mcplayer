package dev.mcai.partner;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Comparator;

/** A bounded approach -> confirmed mining -> pickup sequence, independent of model narration. */
public final class HarvestSkill {
    private enum Phase { APPROACH, MINE, PICKUP }
    private final Minecraft mc;
    private final ClientMotor motor;
    private BlockPos target;
    private Phase phase;
    private boolean mined;
    private int age;
    private String outcome;
    public HarvestSkill(Minecraft mc, ClientMotor motor) { this.mc = mc; this.motor = motor; }
    public boolean busy() { return target != null; }
    public void stop() { target = null; phase = null; mined = false; age = 0; }
    public String start(BlockPos block) {
        if (busy() || motor.busy()) return "FAILED: another physical skill is running";
        if (mc.player == null || mc.level == null || ScreenPolicy.blocksWorld(mc.gui.screen())) return "FAILED: world controls are unavailable";
        if (!mc.level.hasChunkAt(block) || mc.level.getBlockState(block).isAir()
                || mc.level.getBlockState(block).getDestroySpeed(mc.level, block) < 0) return "FAILED: target is not a loaded mineable block";
        outcome = null; mined = false; age = 0;
        if (reachable(block)) {
            String digging = motor.dig(block);
            if (digging.startsWith("STARTED")) { target = block.immutable(); phase = Phase.MINE; }
            return digging;
        }
        ArrayList<BlockPos> candidates = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int dy = -2; dy <= 1; dy++) candidates.add(block.relative(direction).offset(0, dy, 0));
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
                String pickup = motor.collect();
                if (pickup.startsWith("STARTED")) phase = Phase.PICKUP;
                else finish("OK: block mined; no visible dropped item remained, inspect inventory");
            }
            case PICKUP -> finish("OK: harvest sequence ended; inspect actual inventory count");
        }
    }
    private void finish(String result) { outcome = result; stop(); }
    public String drainOutcome() { String value = outcome; outcome = null; return value; }
}
