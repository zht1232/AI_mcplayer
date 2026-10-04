package dev.mcai.partner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;

/** Bounded client-thread movement and vanilla interactions. No server world edits. */
public final class ClientMotor {
    private record Stand(BlockPos floor, double height) { Vec3 center() { return new Vec3(floor.getX() + .5, height, floor.getZ() + .5); } }
    private record Candidate(Stand stand, double cost, double estimate) {}
    private final Minecraft mc;
    private final ArrayDeque<String> results = new ArrayDeque<>();
    private final ArrayDeque<Stand> route = new ArrayDeque<>();
    private BlockPos digging;
    private BlockState diggingOriginal;
    private BlockState diggingConfirmed;
    private int taskTicks;
    private int stillTicks;
    private Vec3 lastPosition;
    private int eatingTicks;
    private int restoreSlot = -1;
    private String following;
    private int followTicks;
    private UUID collecting;
    private boolean active;
    private Vec3 destination;
    private final int range;

    public ClientMotor(Minecraft mc, int range) { this.mc = mc; this.range = range; }
    public boolean busy() { return !route.isEmpty() || digging != null || eatingTicks > 0 || collecting != null || following != null; }
    public Vec3 destination() { return destination; }
    public String status() { return digging != null ? "digging " + digging : eatingTicks > 0 ? "eating" : following != null ? "following " + following : !route.isEmpty() ? "walking " + route.size() : "idle"; }
    public int inventoryCount(String id) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int slot = 0; slot < mc.player.getInventory().getContainerSize(); slot++) {
            ItemStack item = mc.player.getInventory().getItem(slot);
            if (BuiltInRegistries.ITEM.getKey(item.getItem()).toString().equals(id)) count += item.getCount();
        }
        return count;
    }
    public void active(boolean enabled) { active = enabled; if (!enabled) stop(); }
    private void result(String value) { results.addLast(value); while (results.size() > 16) results.removeFirst(); }
    public List<String> drainResults() { List<String> values = List.copyOf(results); results.clear(); return values; }
    public void stop() {
        route.clear(); destination = null; digging = null; diggingConfirmed = null; following = null; collecting = null;
        eatingTicks = 0; taskTicks = 0; stillTicks = 0; followTicks = 0;
        if (mc.gameMode != null) { mc.gameMode.stopDestroyBlock(); if (mc.player != null && mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player); }
        restoreFoodSlot(); releaseKeys();
    }
    private void releaseKeys() {
        mc.options.keyUp.setDown(false); mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false); mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false); mc.options.keySprint.setDown(false);
        mc.options.keyUse.setDown(false); mc.options.keyAttack.setDown(false);
    }
    private void ready() {
        if (!active || mc.level == null || mc.player == null || mc.gameMode == null || !mc.player.isAlive()) throw new IllegalStateException("AI 未开启或玩家不可行动");
        if (mc.gui.screen() != null) throw new IllegalStateException("先关闭当前界面，再执行世界动作");
    }
    private boolean hazard(BlockState state) {
        return state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.SWEET_BERRY_BUSH);
    }
    private Stand stand(BlockPos floor) {
        if (!mc.level.hasChunkAt(floor) || Math.abs(floor.getY() - mc.player.getY()) > range) return null;
        BlockState state = mc.level.getBlockState(floor);
        if (hazard(state) || !state.getFluidState().isEmpty()) return null;
        VoxelShape shape = state.getCollisionShape(mc.level, floor, CollisionContext.of(mc.player));
        if (shape.isEmpty()) return null;
        double height = floor.getY() + shape.max(Direction.Axis.Y);
        if (height > floor.getY() + 1.01) return null;
        double width = mc.player.getBbWidth() / 2.0;
        AABB box = new AABB(floor.getX() + .5 - width, height + .001, floor.getZ() + .5 - width,
                floor.getX() + .5 + width, height + mc.player.getBbHeight(), floor.getZ() + .5 + width);
        if (!mc.level.noCollision(mc.player, box)) return null;
        BlockPos feet = BlockPos.containing(floor.getX() + .5, height + .01, floor.getZ() + .5);
        for (BlockPos occupied : BlockPos.betweenClosed(feet, BlockPos.containing(floor.getX() + .5, height + mc.player.getBbHeight() - .01, floor.getZ() + .5))) {
            BlockState occupiedState = mc.level.getBlockState(occupied);
            if (hazard(occupiedState) || !occupiedState.getFluidState().isEmpty()) return null;
        }
        return new Stand(floor.immutable(), height);
    }
    private Stand nearFoot(BlockPos cell, double desiredHeight) {
        Stand best = null; double distance = Double.MAX_VALUE;
        for (int dy = -2; dy <= 1; dy++) {
            Stand candidate = stand(cell.offset(0, dy, 0));
            if (candidate != null && Math.abs(candidate.height - desiredHeight) < distance) {
                best = candidate; distance = Math.abs(candidate.height - desiredHeight);
            }
        }
        return best;
    }
    public String moveTo(BlockPos target) {
        ready();
        if (mc.player.position().distanceTo(Vec3.atCenterOf(target)) > range) return "FAILED: navigation target exceeds configured local range";
        Stand start = nearFoot(mc.player.blockPosition(), mc.player.getY());
        Stand end = nearFoot(target, target.getY());
        if (start == null || end == null) return "FAILED: no safe standing position at start or target";
        List<Stand> found = plan(start, end);
        if (found == null) return "FAILED: route search exhausted or no safe route within loaded terrain";
        stop(); destination = end.center(); route.addAll(found); lastPosition = mc.player.position();
        return route.isEmpty() ? "OK: already at target" : "STARTED: walking; await arrival result";
    }
    private double heuristic(Stand a, Stand b) {
        return Math.abs(a.floor.getX() - b.floor.getX()) + Math.abs(a.floor.getZ() - b.floor.getZ()) + Math.abs(a.height - b.height);
    }
    private boolean stepAllowed(Stand current, Stand next) {
        return next != null && next.height - current.height <= 1.01 && current.height - next.height <= 2.01;
    }
    private List<Stand> plan(Stand start, Stand end) {
        PriorityQueue<Candidate> frontier = new PriorityQueue<>(Comparator.comparingDouble(Candidate::estimate));
        Map<BlockPos, Double> costs = new HashMap<>(); Map<BlockPos, Stand> parent = new HashMap<>();
        frontier.add(new Candidate(start, 0, heuristic(start, end))); costs.put(start.floor, 0.0);
        int expanded = 0;
        while (!frontier.isEmpty() && expanded++ < 2500) {
            Candidate candidate = frontier.poll(); Stand current = candidate.stand;
            if (candidate.cost > costs.getOrDefault(current.floor, Double.MAX_VALUE)) continue;
            if (current.floor.equals(end.floor)) {
                ArrayList<Stand> route = new ArrayList<>(); Stand step = current;
                while (!step.floor.equals(start.floor)) { route.add(step); step = parent.get(step.floor); }
                Collections.reverse(route); return route;
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                for (int dy = -2; dy <= 1; dy++) {
                    BlockPos nextFloor = current.floor.relative(direction).offset(0, dy, 0);
                    if (Math.abs(nextFloor.getX() - start.floor.getX()) > range || Math.abs(nextFloor.getZ() - start.floor.getZ()) > range) continue;
                    Stand next = stand(nextFloor);
                    if (!stepAllowed(current, next)) continue;
                    double cost = candidate.cost + 1 + Math.max(0, next.height - current.height) * .8;
                    if (cost < costs.getOrDefault(next.floor, Double.MAX_VALUE)) {
                        costs.put(next.floor, cost); parent.put(next.floor, current);
                        frontier.add(new Candidate(next, cost, cost + heuristic(next, end)));
                    }
                }
            }
        }
        return null;
    }
    private void look(Vec3 at) {
        Vec3 delta = at.subtract(mc.player.getEyePosition());
        float yaw = (float) (Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)));
        float turn = net.minecraft.util.Mth.wrapDegrees(yaw - mc.player.getYRot());
        mc.player.setYRot(mc.player.getYRot() + Math.clamp(turn, -15f, 15f));
        mc.player.setXRot(Math.clamp(pitch, -85f, 85f));
    }
    public String dig(BlockPos position) {
        ready(); if (busy()) return "FAILED: physical task already active";
        BlockHitResult hit = visibleHit(position);
        if (hit == null) return "FAILED: block is not visible in current reach; move closer first";
        BlockState state = mc.level.getBlockState(position);
        if (state.isAir() || state.getDestroySpeed(mc.level, position) < 0) return "FAILED: block cannot be mined";
        look(hit.getLocation()); digging = position.immutable(); diggingOriginal = state; diggingConfirmed = null; taskTicks = 0;
        mc.gameMode.startDestroyBlock(position, hit.getDirection());
        return "STARTED: mining; await actual block change";
    }
    private BlockHitResult visibleHit(BlockPos position) {
        if (!mc.level.hasChunkAt(position)) return null;
        Vec3 eye = mc.player.getEyePosition(); Vec3 center = Vec3.atCenterOf(position);
        if (eye.distanceTo(center) > mc.player.blockInteractionRange()) return null;
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(position) ? hit : null;
    }
    public String useBlock(BlockPos position) {
        ready(); if (busy()) return "FAILED: finish physical task first";
        BlockHitResult hit = visibleHit(position);
        if (hit == null) return "FAILED: block not visible or out of reach";
        look(hit.getLocation()); mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);
        return "SENT: block interaction; inspect the server's resulting UI or world state";
    }
    public String place(BlockPos support, String face) {
        ready(); if (busy()) return "FAILED: finish physical task first";
        Direction side = Direction.byName(face.toLowerCase(Locale.ROOT));
        if (side == null) return "FAILED: invalid block face";
        BlockHitResult seen = visibleHit(support);
        if (seen == null) return "FAILED: supporting block not visible or out of reach";
        Vec3 hitPoint = Vec3.atCenterOf(support).add(side.getStepX() * .501, side.getStepY() * .501, side.getStepZ() * .501);
        BlockHitResult direct = mc.level.clip(new ClipContext(mc.player.getEyePosition(), hitPoint, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (!direct.getBlockPos().equals(support) || direct.getDirection() != side) return "FAILED: selected supporting face is not visible";
        look(hitPoint); mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, direct); mc.player.swing(InteractionHand.MAIN_HAND);
        return "SENT: placement/use action; inspect actual block and item changes before claiming success";
    }
    public String select(int slot) { ready(); if (slot < 0 || slot > 8) return "FAILED: hotbar index must be 0–8"; mc.player.getInventory().setSelectedSlot(slot); return "OK: selected hotbar " + slot; }
    public String eat() {
        ready(); if (busy()) return "FAILED: physical task active";
        if (!mc.player.getFoodData().needsFood()) return "OK: not hungry";
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.has(DataComponents.FOOD)) {
                restoreSlot = mc.player.getInventory().getSelectedSlot(); mc.player.getInventory().setSelectedSlot(slot);
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND); eatingTicks = 60;
                return "STARTED: eating from hotbar";
            }
        }
        return "FAILED: no food in hotbar; move food to hotbar through inventory UI";
    }
    private void restoreFoodSlot() { if (restoreSlot >= 0 && mc.player != null) mc.player.getInventory().setSelectedSlot(restoreSlot); restoreSlot = -1; }
    public void serverBlockUpdate(BlockPos position, BlockState state) {
        if (digging != null && digging.equals(position)) diggingConfirmed = state;
    }
    public String attack() {
        ready(); if (busy()) return "FAILED: physical task active";
        Entity nearest = null; double distance = mc.player.entityInteractionRange();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Monster monster) || !monster.isAlive()) continue;
            double found = mc.player.distanceTo(entity);
            if (found < distance && mc.player.hasLineOfSight(entity)) { nearest = entity; distance = found; }
        }
        if (nearest == null) return "FAILED: no visible hostile mob in reach";
        if (mc.player.getAttackStrengthScale(0) < .9f) return "WAIT: attack cooldown";
        look(nearest.getEyePosition()); mc.gameMode.attack(mc.player, nearest); mc.player.swing(InteractionHand.MAIN_HAND);
        return "SENT: attack; inspect health and combat feedback";
    }
    public String follow(String name) { ready(); stop(); following = name; return "STARTED: following " + name; }
    public String collect() {
        ready(); ItemEntity closest = null; double distance = range;
        for (Entity entity : mc.level.entitiesForRendering()) if (entity instanceof ItemEntity item && mc.player.hasLineOfSight(item) && mc.player.distanceTo(item) < distance) { closest = item; distance = mc.player.distanceTo(item); }
        if (closest == null) return "FAILED: no visible dropped item nearby";
        String result = moveTo(closest.blockPosition()); if (result.startsWith("FAILED")) return result;
        collecting = closest.getUUID(); return "STARTED: walking to dropped item; normal server pickup applies";
    }
    public void tick() {
        if (!active) return;
        releaseKeys();
        if (mc.player == null || mc.level == null || !mc.player.isAlive()) { stop(); return; }
        if (mc.gui.screen() != null) return;
        if (mc.player.isInWater() && mc.player.getAirSupply() < 120) { mc.options.keyJump.setDown(true); mc.options.keyUp.setDown(false); return; }
        if (eatingTicks > 0) {
            eatingTicks--; mc.options.keyUse.setDown(true);
            if (eatingTicks < 55 && !mc.player.isUsingItem()) { eatingTicks = 0; restoreFoodSlot(); result("Eating ended; current food level=" + mc.player.getFoodData().getFoodLevel()); }
            else if (eatingTicks == 0) { mc.gameMode.releaseUsingItem(mc.player); restoreFoodSlot(); result("Eating timed out; inspect food level"); }
            return;
        }
        if (digging != null) {
            if (diggingConfirmed != null && !diggingConfirmed.equals(diggingOriginal)) { result("OK: server confirmed block state change at " + digging); digging = null; mc.gameMode.stopDestroyBlock(); return; }
            if (++taskTicks > 300) { result("FAILED: mining timed out without server confirmation at " + digging); digging = null; mc.gameMode.stopDestroyBlock(); return; }
            if (!mc.level.getBlockState(digging).equals(diggingOriginal)) return; // Prediction is not confirmation.
            BlockHitResult hit = visibleHit(digging);
            if (hit == null) { result("FAILED: mining lost reach at " + digging); digging = null; mc.gameMode.stopDestroyBlock(); return; }
            look(hit.getLocation()); mc.gameMode.continueDestroyBlock(digging, hit.getDirection()); mc.player.swing(InteractionHand.MAIN_HAND); return;
        }
        // A route step resets taskTicks. Following needs its own clock or target refresh
        // can starve while walking, and movement timeouts can be reset by replanning.
        if (following != null && --followTicks <= 0) {
            String name = following;
            Entity target = mc.level.players().stream().filter(p -> p.getName().getString().equalsIgnoreCase(name)).findFirst().orElse(null);
            if (target == null) { stop(); result("FAILED: followed player is not loaded"); }
            else if (mc.player.distanceTo(target) <= 3) {
                route.clear(); destination = null; taskTicks = 0; stillTicks = 0; followTicks = 20;
            } else {
                if (route.isEmpty() || destination == null || destination.distanceTo(target.position()) > 1.5) {
                    String outcome = moveTo(target.blockPosition());
                    if (outcome.startsWith("FAILED")) { stop(); result(outcome); }
                    else following = name;
                }
                followTicks = 20;
            }
        }
        if (collecting != null && mc.level.entitiesForRendering() != null) {
            boolean exists = false; for (Entity entity : mc.level.entitiesForRendering()) if (entity.getUUID().equals(collecting)) { exists = true; break; }
            if (!exists) { result("Dropped item is no longer visible; inspect inventory to verify pickup"); collecting = null; }
        }
        if (route.isEmpty()) {
            if (collecting != null && ++taskTicks > 60) { result("FAILED: dropped item pickup not confirmed"); collecting = null; }
            return;
        }
        Stand next = route.peekFirst();
        if (mc.player.position().distanceTo(next.center()) < .45) {
            route.removeFirst(); taskTicks = 0; stillTicks = 0;
            if (route.isEmpty()) result("OK: arrived at route target " + next.center());
            return;
        }
        if (stand(next.floor) == null) { result("FAILED: route terrain changed"); stop(); return; }
        if (++taskTicks > 240) { result("FAILED: movement timed out"); stop(); return; }
        if (lastPosition != null && lastPosition.distanceTo(mc.player.position()) < .025) stillTicks++; else stillTicks = 0;
        lastPosition = mc.player.position();
        if (stillTicks > 60) { result("FAILED: movement stuck or server rejected movement"); stop(); return; }
        look(next.center().add(0, mc.player.getEyeHeight(), 0));
        Vec3 delta = next.center().subtract(mc.player.position());
        float targetYaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        if (Math.abs(net.minecraft.util.Mth.wrapDegrees(targetYaw - mc.player.getYRot())) < 50) mc.options.keyUp.setDown(true);
        if (next.height > mc.player.getY() + .2 || stillTicks > 12) mc.options.keyJump.setDown(true);
    }
    /** At most eight nearby positions connected to the player by the same dry walking rules as moveTo. */
    private JsonArray safeDestinations(int radius) {
        JsonArray destinations = new JsonArray();
        Stand start = nearFoot(mc.player.blockPosition(), mc.player.getY());
        if (start == null) return destinations;
        int scanRange = Math.min(radius, 6);
        ArrayDeque<Stand> frontier = new ArrayDeque<>(); frontier.add(start);
        Map<BlockPos, Integer> steps = new HashMap<>(); steps.put(start.floor, 0);
        Map<BlockPos, Stand> cache = new HashMap<>(); cache.put(start.floor, start);
        Stand[] choices = new Stand[8]; double[] scores = new double[8]; Arrays.fill(scores, Double.MAX_VALUE);
        int expanded = 0;
        while (!frontier.isEmpty() && expanded++ < 128) {
            Stand current = frontier.removeFirst();
            double dx = current.center().x - mc.player.getX(), dz = current.center().z - mc.player.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance >= 1.5 && distance <= scanRange) {
                int direction = Math.floorMod((int) Math.round(Math.atan2(dz, dx) / (Math.PI / 4)), 8);
                double score = Math.abs(distance - Math.min(scanRange, 4)) + steps.get(current.floor) * .02;
                if (score < scores[direction]) { choices[direction] = current; scores[direction] = score; }
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) for (int dy = -2; dy <= 1; dy++) {
                BlockPos nextFloor = current.floor.relative(direction).offset(0, dy, 0);
                if (Math.abs(nextFloor.getX() - start.floor.getX()) > scanRange
                        || Math.abs(nextFloor.getZ() - start.floor.getZ()) > scanRange || steps.containsKey(nextFloor)) continue;
                if (!cache.containsKey(nextFloor)) cache.put(nextFloor, stand(nextFloor));
                Stand next = cache.get(nextFloor);
                if (!stepAllowed(current, next)) continue;
                steps.put(nextFloor, steps.get(current.floor) + 1); frontier.addLast(next);
            }
        }
        String[] directions = {"east", "southeast", "south", "southwest", "west", "northwest", "north", "northeast"};
        for (int direction = 0; direction < choices.length; direction++) {
            Stand choice = choices[direction]; if (choice == null) continue;
            JsonObject position = new JsonObject();
            position.addProperty("x", choice.floor.getX()); position.addProperty("y", (int) Math.floor(choice.height)); position.addProperty("z", choice.floor.getZ());
            position.addProperty("direction", directions[direction]);
            position.addProperty("distance", Math.round(mc.player.position().distanceTo(choice.center()) * 10) / 10.0);
            position.addProperty("pathSteps", steps.get(choice.floor)); destinations.add(position);
        }
        return destinations;
    }
    public JsonObject snapshot(int radius) {
        JsonObject state = new JsonObject();
        if (mc.player == null || mc.level == null) return state;
        state.addProperty("x", mc.player.getX()); state.addProperty("y", mc.player.getY()); state.addProperty("z", mc.player.getZ());
        state.addProperty("health", mc.player.getHealth()); state.addProperty("food", mc.player.getFoodData().getFoodLevel());
        state.addProperty("air", mc.player.getAirSupply()); state.addProperty("dimension", mc.level.dimension().identifier().toString());
        state.addProperty("task", status()); state.addProperty("hotbarSlot", mc.player.getInventory().getSelectedSlot());
        state.add("safeDestinations", safeDestinations(radius));
        JsonArray blocks = new JsonArray(); BlockPos origin = mc.player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -3, -radius), origin.offset(radius, 5, radius))) {
            if (!mc.level.hasChunkAt(pos)) continue;
            BlockState block = mc.level.getBlockState(pos);
            if (block.isAir()) continue;
            String id = BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
            if (!(block.is(BlockTags.LOGS) || block.is(BlockTags.CROPS) || id.contains("ore") || id.contains("chest") || id.contains("crafting") || id.contains("furnace") || id.contains("farmland"))) continue;
            // Only a ray-visible surface is exposed; hidden ore is never reported.
            BlockHitResult hit = mc.level.clip(new ClipContext(mc.player.getEyePosition(), Vec3.atCenterOf(pos), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
            if (!hit.getBlockPos().equals(pos)) continue;
            JsonObject entry = new JsonObject(); entry.addProperty("id", id); entry.addProperty("x", pos.getX()); entry.addProperty("y", pos.getY()); entry.addProperty("z", pos.getZ()); entry.addProperty("state", block.toString()); blocks.add(entry);
            if (blocks.size() >= 48) break;
        }
        state.add("visibleBlocks", blocks);
        JsonArray entities = new JsonArray();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || mc.player.distanceTo(entity) > radius * 2 || !mc.player.hasLineOfSight(entity)) continue;
            JsonObject entry = new JsonObject(); entry.addProperty("name", entity.getName().getString()); entry.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            entry.addProperty("x", entity.getX()); entry.addProperty("y", entity.getY()); entry.addProperty("z", entity.getZ()); entities.add(entry); if (entities.size() >= 24) break;
        }
        state.add("visibleEntities", entities); return state;
    }
}
