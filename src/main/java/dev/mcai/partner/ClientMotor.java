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
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FallingBlock;
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
    private final ArrayDeque<BlockPos> escapeBlocks = new ArrayDeque<>();
    private BlockPos escapeExit;
    private boolean escaping;
    private int survivalTicks, escapeCooldown, foodRetry;
    private int combatSupplyRetry;
    private boolean defending;
    private boolean retreating;
    private int defenceQuietTicks;
    private int recentDamageTicks;
    private int restoreCombatSlot = -1;
    private float lastHealth = Float.NaN;

    public ClientMotor(Minecraft mc, int range) { this.mc = mc; this.range = range; }
    public boolean busy() { return escaping || defending || !route.isEmpty() || digging != null || eatingTicks > 0 || collecting != null || following != null; }
    public boolean defending() { return defending; }
    public boolean survivalBusy() { return defending || escaping || eatingTicks > 0; }
    public Vec3 destination() { return destination; }
    public String status() { return defending ? retreating ? "defending: retreating" : "defending: fighting" : digging != null ? "digging " + digging : eatingTicks > 0 ? "eating" : following != null ? "following " + following : !route.isEmpty() ? "walking " + route.size() : "idle"; }
    public int inventoryCount(String id) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int slot = 0; slot < mc.player.getInventory().getContainerSize(); slot++) {
            ItemStack item = mc.player.getInventory().getItem(slot);
            if (BuiltInRegistries.ITEM.getKey(item.getItem()).toString().equals(id)) count += item.getCount();
        }
        return count;
    }
    public void active(boolean enabled) { active = enabled; lastHealth = Float.NaN; if (!enabled) stop(); }
    private void result(String value) { results.addLast(value); while (results.size() > 16) results.removeFirst(); }
    public List<String> drainResults() { List<String> values = List.copyOf(results); results.clear(); return values; }
    public void stop() {
        escaping = false; escapeBlocks.clear(); escapeExit = null;
        route.clear(); destination = null; digging = null; diggingConfirmed = null; following = null; collecting = null;
        eatingTicks = 0; taskTicks = 0; stillTicks = 0; followTicks = 0;
        defending = false; retreating = false; defenceQuietTicks = 0; recentDamageTicks = 0;
        if (mc.gameMode != null) { mc.gameMode.stopDestroyBlock(); if (mc.player != null && mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player); }
        restoreFoodSlot(); restoreCombatSlot(); releaseKeys();
    }
    private void releaseKeys() {
        mc.options.keyUp.setDown(false); mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false); mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false); mc.options.keySprint.setDown(false);
        mc.options.keyUse.setDown(false); mc.options.keyAttack.setDown(false);
    }
    private void ready() {
        if (!active || mc.level == null || mc.player == null || mc.gameMode == null || !mc.player.isAlive()) throw new IllegalStateException("AI 未开启或玩家不可行动");
        if (ScreenPolicy.blocksWorld(mc.gui.screen())) throw new IllegalStateException("先关闭当前界面，再执行世界动作");
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
    public boolean canMineFrom(BlockPos standing, BlockPos target) {
        Stand candidate = nearFoot(standing, standing.getY()); if (candidate == null) return false;
        Vec3 eye = candidate.center().add(0, mc.player.getEyeHeight(), 0);
        if (eye.distanceTo(Vec3.atCenterOf(target)) > mc.player.blockInteractionRange() - .1) return false;
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, Vec3.atCenterOf(target), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
    }
    public boolean canMineNow(BlockPos target) { return mc.player != null && mc.level != null && visibleHit(target) != null; }
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
        return beginDig(position);
    }
    private String beginDig(BlockPos position) {
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
        Vec3 hitPoint = Vec3.atCenterOf(support).add(side.getStepX() * .499, side.getStepY() * .499, side.getStepZ() * .499);
        BlockHitResult direct = mc.level.clip(new ClipContext(mc.player.getEyePosition(), hitPoint, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (!direct.getBlockPos().equals(support) || direct.getDirection() != side) return "FAILED: selected supporting face is not visible";
        look(hitPoint); mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, direct); mc.player.swing(InteractionHand.MAIN_HAND);
        return "SENT: placement/use action; inspect actual block and item changes before claiming success";
    }
    public String select(int slot) { ready(); if (slot < 0 || slot > 8) return "FAILED: hotbar index must be 0–8"; mc.player.getInventory().setSelectedSlot(slot); return "OK: selected hotbar " + slot; }
    public String eat() {
        ready(); if (busy()) return "FAILED: physical task active";
        if (!mc.player.getFoodData().needsFood()) return "OK: not hungry";
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (safeFood(stack)) {
                int hotbar = slot;
                if (slot >= 9) {
                    if (mc.player.containerMenu != mc.player.inventoryMenu) return "FAILED: close the container before eating from main inventory";
                    hotbar = 8;
                    for (int empty = 0; empty < 9; empty++) if (mc.player.getInventory().getItem(empty).isEmpty()) { hotbar = empty; break; }
                    mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, slot, hotbar, ContainerInput.SWAP, mc.player);
                }
                restoreSlot = mc.player.getInventory().getSelectedSlot(); mc.player.getInventory().setSelectedSlot(hotbar);
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND); eatingTicks = 60;
                return "STARTED: eating existing food without opening inventory";
            }
        }
        return "FAILED: no safe food in inventory; ask players for food";
    }
    private boolean safeFood(ItemStack stack) {
        return stack.has(DataComponents.FOOD) && !stack.is(Items.ROTTEN_FLESH) && !stack.is(Items.PUFFERFISH)
                && !stack.is(Items.SPIDER_EYE) && !stack.is(Items.POISONOUS_POTATO) && !stack.is(Items.CHICKEN) && !stack.is(Items.SUSPICIOUS_STEW);
    }
    public boolean hasFood() {
        if (mc.player == null) return false;
        for (int slot = 0; slot < 36; slot++) if (safeFood(mc.player.getInventory().getItem(slot))) return true;
        return false;
    }
    private boolean healingPotion(ItemStack stack) {
        if (!stack.is(Items.POTION)) return false;
        var contents = stack.get(DataComponents.POTION_CONTENTS); if (contents == null) return false;
        boolean heals = false;
        for (var effect : contents.getAllEffects()) {
            if (effect.getEffect().value().getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL) return false;
            if (effect.getEffect().equals(net.minecraft.world.effect.MobEffects.INSTANT_HEALTH) || effect.getEffect().equals(net.minecraft.world.effect.MobEffects.REGENERATION)) heals = true;
        }
        return heals;
    }
    public String useHealingItem() {
        ready(); if (busy()) return "FAILED: physical task active";
        for (int slot = 0; slot < 36; slot++) if (healingPotion(mc.player.getInventory().getItem(slot))) {
            int hotbar = slot;
            if (slot >= 9) {
                if (mc.player.containerMenu != mc.player.inventoryMenu) return "FAILED: finish the open container first";
                hotbar = 8;
                for (int empty = 0; empty < 9; empty++) if (mc.player.getInventory().getItem(empty).isEmpty()) { hotbar = empty; break; }
                mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, slot, hotbar, ContainerInput.SWAP, mc.player);
            }
            restoreSlot = mc.player.getInventory().getSelectedSlot(); mc.player.getInventory().setSelectedSlot(hotbar);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND); eatingTicks = 60;
            return "STARTED: using an existing safe healing/regeneration potion; verify server health";
        }
        return "FAILED: no safe healing potion in inventory";
    }
    private void restoreFoodSlot() { if (restoreSlot >= 0 && mc.player != null) mc.player.getInventory().setSelectedSlot(restoreSlot); restoreSlot = -1; }
    public void serverBlockUpdate(BlockPos position, BlockState state) {
        if (digging != null && digging.equals(position)) diggingConfirmed = state;
    }
    public String attack() {
        ready(); if (busy()) return "FAILED: physical task active";
        Entity nearest = null; double distance = mc.player.entityInteractionRange();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Mob mob) || !hostile(mob)) continue;
            double found = mc.player.distanceTo(entity);
            if (found < distance && mc.player.hasLineOfSight(entity)) { nearest = entity; distance = found; }
        }
        if (nearest == null) return "FAILED: no visible hostile mob in reach";
        if (mc.player.getAttackStrengthScale(0) < .9f) return "WAIT: attack cooldown";
        look(nearest.getEyePosition()); mc.gameMode.attack(mc.player, nearest); mc.player.swing(InteractionHand.MAIN_HAND);
        return "SENT: attack; inspect health and combat feedback";
    }
    /** Only client-visible hostile monsters are eligible. Players and peaceful neutral mobs are excluded. */
    private boolean hostile(Mob mob) {
        return mob instanceof Enemy && mob.isAlive()
                && (!(mob instanceof NeutralMob) || mob.isAggressive())
                && (!(mob instanceof AbstractPiglin) || mob.isAggressive());
    }
    private void restoreCombatSlot() {
        if (restoreCombatSlot >= 0 && mc.player != null) mc.player.getInventory().setSelectedSlot(restoreCombatSlot);
        restoreCombatSlot = -1;
    }
    private void selectCombatWeapon() {
        int selected = mc.player.getInventory().getSelectedSlot();
        int best = selected;
        double damage = weaponDamage(mc.player.getInventory().getItem(selected));
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!(stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES))) continue;
            double candidate = weaponDamage(stack);
            if (candidate > damage) { best = slot; damage = candidate; }
        }
        if (best >= 9 && mc.player.containerMenu == mc.player.inventoryMenu) {
            int hotbar = 8;
            for (int empty = 0; empty < 9; empty++) if (mc.player.getInventory().getItem(empty).isEmpty()) { hotbar = empty; break; }
            mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, best, hotbar, ContainerInput.SWAP, mc.player); best = hotbar;
        }
        if (best < 9 && best != selected) { restoreCombatSlot = selected; mc.player.getInventory().setSelectedSlot(best); }
    }
    private double weaponDamage(ItemStack stack) {
        var modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        return modifiers == null ? 1 : modifiers.compute(Attributes.ATTACK_DAMAGE, 1, EquipmentSlot.MAINHAND);
    }
    /** Runs before planning and physical work, so a pending HTTP request cannot delay self-defence. */
    private boolean defend() {
        if (combatSupplyRetry > 0) combatSupplyRetry--;
        if (escaping) return false; // Clearing an exit is part of the same emergency, not a competing fight.
        float health = mc.player.getHealth();
        if (!Float.isNaN(lastHealth) && health < lastHealth) recentDamageTicks = 60;
        else if (recentDamageTicks > 0) recentDamageTicks--;
        lastHealth = health;
        Mob threat = null; double nearest = Double.MAX_VALUE;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Mob mob) || !hostile(mob) || !mc.player.hasLineOfSight(entity)) continue;
            double distance = mc.player.distanceTo(entity);
            double triggerRange = mob instanceof Creeper ? 4.5 : recentDamageTicks > 0 ? 6 : 3.5;
            if (distance <= triggerRange && distance < nearest) { threat = mob; nearest = distance; }
        }
        if (threat == null) {
            if (!defending) return false;
            if (++defenceQuietTicks < 20) return true;
            defending = false; retreating = false; restoreCombatSlot();
            result("DEFENCE ENDED: no visible hostile in the immediate threat radius; re-observe before resuming work");
            return false;
        }
        if (!defending) {
            boolean interrupted = busy();
            int damageMemory = recentDamageTicks;
            stop(); recentDamageTicks = damageMemory; defending = true; selectCombatWeapon();
            result("DEFENCE STARTED: immediate visible hostile; model request and queued world actions must be invalidated");
            if (interrupted) result("FAILED: physical work interrupted for immediate self-defence; re-plan when safe");
        }
        defenceQuietTicks = 0;
        if (health <= 10 && nearest >= 2.5 && !(threat instanceof Creeper) && eatingTicks == 0 && combatSupplyRetry == 0) {
            // Using supplies is part of defence, so an ongoing fight cannot starve the eating state.
            defending = false;
            String supplies = useHealingItem();
            if (!supplies.startsWith("STARTED") && mc.player.getFoodData().needsFood() && hasFood()) supplies = eat();
            defending = true; combatSupplyRetry = 80;
            if (supplies.startsWith("STARTED")) result("SURVIVAL INTERRUPT: using existing supplies while keeping distance from the threat");
        }
        retreating = health <= 6 || eatingTicks > 0 || threat instanceof Creeper;
        if (retreating) {
            // Retreat only over a neighbouring dry, collision-free standing cell. Never back into an unseen drop.
            Vec3 away = mc.player.position().subtract(threat.position()).multiply(1, 0, 1);
            if (away.lengthSqr() < .01) away = new Vec3(1, 0, 0);
            Vec3 direction = away.normalize();
            Stand start = nearFoot(mc.player.blockPosition(), mc.player.getY());
            Stand escape = nearFoot(BlockPos.containing(mc.player.position().add(direction.scale(1.2))), mc.player.getY());
            if (start != null && (!stepAllowed(start, escape) || start.floor.equals(escape.floor))) {
                Stand alternate = null; double safest = nearest * nearest;
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    Stand candidate = nearFoot(mc.player.blockPosition().relative(side), mc.player.getY());
                    if (!stepAllowed(start, candidate) || start.floor.equals(candidate.floor)) continue;
                    double separation = candidate.center().distanceToSqr(threat.position());
                    if (separation > safest) { safest = separation; alternate = candidate; }
                }
                if (alternate != null) { escape = alternate; direction = escape.center().subtract(mc.player.position()).multiply(1,0,1).normalize(); }
            }
            if (start != null && stepAllowed(start, escape) && !start.floor.equals(escape.floor)) {
                look(escape.center().add(0, mc.player.getEyeHeight(), 0));
                float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
                if (Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - mc.player.getYRot())) < 35) mc.options.keyUp.setDown(true);
                if (escape.height > mc.player.getY() + .2) mc.options.keyJump.setDown(true);
                if (mc.player.getFoodData().getFoodLevel() > 6) mc.options.keySprint.setDown(true);
            } else if (surrounded()) {
                defending = false;
                String opened = escape();
                if (opened.startsWith("STARTED")) return false;
                defending = true;
                result("NEED_HELP: threatened and trapped without a safe breakable exit");
            }
            if (eatingTicks == 0 && !(threat instanceof Creeper) && mc.player.isWithinEntityInteractionRange(threat, 0)
                    && mc.player.getAttackStrengthScale(0) >= .9f) {
                // Retreating can be obstructed; still fend off a reachable attacker with normal cooldown.
                if (!mc.options.keyUp.isDown()) look(threat.getEyePosition());
                Vec3 aim = threat.getEyePosition().subtract(mc.player.getEyePosition());
                if (Math.abs(net.minecraft.util.Mth.wrapDegrees((float)Math.toDegrees(Math.atan2(-aim.x, aim.z))-mc.player.getYRot())) < 25) {
                    mc.gameMode.attack(mc.player, threat); mc.player.swing(InteractionHand.MAIN_HAND);
                }
            }
            return true;
        }
        look(threat.getEyePosition());
        // Normal vanilla range and attack cooldown apply, even though this is a local reflex.
        boolean inReach = mc.player.isWithinEntityInteractionRange(threat, 0);
        if (!inReach) {
            Vec3 approach = threat.position().subtract(mc.player.position()).multiply(1, 0, 1).normalize();
            Stand start = nearFoot(mc.player.blockPosition(), mc.player.getY());
            Stand step = nearFoot(BlockPos.containing(mc.player.position().add(approach.scale(1.2))), mc.player.getY());
            if (start != null && stepAllowed(start, step) && !start.floor.equals(step.floor)) {
                float yaw = (float) Math.toDegrees(Math.atan2(-approach.x, approach.z));
                if (Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - mc.player.getYRot())) < 25) mc.options.keyUp.setDown(true);
                if (step.height > mc.player.getY() + .2) mc.options.keyJump.setDown(true);
            }
        }
        if (inReach && mc.player.getAttackStrengthScale(0) >= .9f) {
            Vec3 delta = threat.getEyePosition().subtract(mc.player.getEyePosition());
            float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
            if (Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - mc.player.getYRot())) < 25) {
                mc.gameMode.attack(mc.player, threat); mc.player.swing(InteractionHand.MAIN_HAND);
            }
        }
        return true;
    }
    public String follow(String name) { ready(); stop(); following = name; return "STARTED: following " + name; }
    public String collect() {
        ready(); ItemEntity closest = null; double distance = range;
        for (Entity entity : mc.level.entitiesForRendering()) if (entity instanceof ItemEntity item && mc.player.hasLineOfSight(item) && mc.player.distanceTo(item) < distance) { closest = item; distance = mc.player.distanceTo(item); }
        if (closest == null) return "FAILED: no visible dropped item nearby";
        String result = moveTo(closest.blockPosition()); if (result.startsWith("FAILED")) return result;
        collecting = closest.getUUID(); return "STARTED: walking to dropped item; normal server pickup applies";
    }
    /** Bounded local escape: clear a cheap reachable obstruction, never a container or unsafe floor. */
    public String escape() {
        ready(); if (busy()) return "FAILED: physical task active";
        Stand start = nearFoot(mc.player.blockPosition(), mc.player.getY());
        if (start == null) return "FAILED: no safe current standing surface";
        BlockPos feet = BlockPos.containing(mc.player.getX(), Math.floor(mc.player.getY() + .1), mc.player.getZ());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            Stand exit = nearFoot(feet.relative(direction, 2), mc.player.getY());
            if (exit == null || !stepAllowed(start, exit)) continue;
            BlockPos lower = feet.relative(direction), upper = lower.above();
            ArrayDeque<BlockPos> breakable = new ArrayDeque<>(); boolean safe = true;
            for (BlockPos block : List.of(upper, lower)) {
                BlockState state = mc.level.getBlockState(block);
                if (state.getCollisionShape(mc.level, block).isEmpty()) continue;
                if (state.hasBlockEntity() || hazard(state) || !state.getFluidState().isEmpty()
                        || state.getBlock() instanceof FallingBlock || mc.level.getBlockState(block.above()).getBlock() instanceof FallingBlock
                        || state.is(Blocks.TNT) || state.getDestroySpeed(mc.level, block) < 0 || state.getDestroySpeed(mc.level, block) > 3) { safe = false; break; }
                if (visibleHit(block) == null && !(block.equals(lower) && breakable.contains(upper))) { safe = false; break; }
                breakable.add(block.immutable());
            }
            if (!safe || breakable.isEmpty()) continue;
            stop(); escaping = true; escapeBlocks.addAll(breakable); escapeExit = BlockPos.containing(exit.center());
            result("SURVIVAL INTERRUPT: clearing a reachable obstruction to escape");
            return "STARTED: bounded escape mining; await server confirmation and movement";
        }
        return "FAILED: no safe breakable escape opening; ask a player for help";
    }
    private boolean surrounded() {
        Stand start = nearFoot(mc.player.blockPosition(), mc.player.getY()); if (start == null) return false;
        for (Direction direction : Direction.Plane.HORIZONTAL) for (int dy = -1; dy <= 1; dy++)
            if (stepAllowed(start, stand(start.floor.relative(direction).offset(0, dy, 0)))) return false;
        return true;
    }
    private boolean survival() {
        survivalTicks++;
        if (foodRetry > 0) foodRetry--;
        if (escapeCooldown > 0) escapeCooldown--;
        if (mc.player.getHealth() <= 10 && eatingTicks == 0 && foodRetry == 0 && !escaping) {
            boolean available = false; for (int slot = 0; slot < 36; slot++) if (healingPotion(mc.player.getInventory().getItem(slot))) { available = true; break; }
            if (available) {
                stop(); String healing = useHealingItem(); foodRetry = 60;
                if (healing.startsWith("STARTED")) { result("SURVIVAL INTERRUPT: using existing healing supplies; wait for real server health"); return true; }
            }
        }
        boolean needFood = mc.player.getFoodData().getFoodLevel() <= 14 || mc.player.getHealth() <= 12 && mc.player.getFoodData().needsFood();
        if (needFood && eatingTicks == 0 && foodRetry == 0 && !escaping) {
            if (hasFood()) {
                stop(); String food = eat(); foodRetry = 20;
                if (food.startsWith("STARTED")) { result("SURVIVAL INTERRUPT: eating existing safe food; server health/hunger will confirm recovery"); return true; }
            } else {
                ItemEntity food = null; double distance = 8;
                for (Entity entity : mc.level.entitiesForRendering()) if (entity instanceof ItemEntity item && safeFood(item.getItem()) && mc.player.hasLineOfSight(item) && mc.player.distanceTo(item) < distance) { food = item; distance = mc.player.distanceTo(item); }
                if (food != null && route.isEmpty()) {
                    stop(); String walked = moveTo(food.blockPosition());
                    if (walked.startsWith("STARTED")) { collecting = food.getUUID(); foodRetry = 60; result("SURVIVAL INTERRUPT: walking to visible dropped food"); return true; }
                }
                foodRetry = 100;
                if (survivalTicks % 100 == 0 || mc.player.getHealth() <= 6) result("NEED_HELP: no safe food in inventory; low health/food needs supplies");
            }
        }
        if (escaping && digging == null && route.isEmpty()) {
            while (!escapeBlocks.isEmpty() && mc.level.getBlockState(escapeBlocks.peekFirst()).getCollisionShape(mc.level, escapeBlocks.peekFirst()).isEmpty()) escapeBlocks.removeFirst();
            if (!escapeBlocks.isEmpty()) {
                String mined = beginDig(escapeBlocks.removeFirst());
                if (!mined.startsWith("STARTED")) { stop(); result(mined); escapeCooldown = 200; }
                return true;
            }
            BlockPos exit = escapeExit;
            Stand exited = nearFoot(exit, mc.player.getY());
            if (exited != null && mc.player.position().distanceTo(exited.center()) < .8) {
                escaping = false; escapeExit = null; escapeCooldown = 200; result("OK: escaped through a server-confirmed opening"); return true;
            }
            escaping = false; String moved = moveTo(exit);
            if (moved.startsWith("STARTED")) { escaping = true; escapeExit = exit; }
            result("Escape opening cleared; " + moved); escapeCooldown = 200; return true;
        }
        if (!busy() && escapeCooldown == 0 && surrounded()) {
            String freed = escape(); escapeCooldown = 200;
            if (freed.startsWith("STARTED")) return true;
            result("NEED_HELP: trapped without a safe breakable exit");
        }
        return false;
    }
    private void tickConsumption() {
        eatingTicks--; mc.options.keyUse.setDown(true);
        if (eatingTicks < 55 && !mc.player.isUsingItem()) { eatingTicks = 0; restoreFoodSlot(); result("Eating ended; current food level=" + mc.player.getFoodData().getFoodLevel()); }
        else if (eatingTicks == 0) { mc.gameMode.releaseUsingItem(mc.player); restoreFoodSlot(); result("Eating timed out; inspect food level"); }
    }
    public void tick() {
        if (!active) return;
        releaseKeys();
        if (mc.player == null || mc.level == null || !mc.player.isAlive()) { stop(); return; }
        if (ScreenPolicy.blocksWorld(mc.gui.screen())) return;
        if (mc.player.isInWater() && mc.player.getAirSupply() < 120) { mc.options.keyJump.setDown(true); mc.options.keyUp.setDown(false); return; }
        if (defend()) { if (eatingTicks > 0) tickConsumption(); return; }
        if (survival()) return;
        if (eatingTicks > 0) {
            tickConsumption();
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
        ArrayList<BlockPos> resources = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -3, -radius), origin.offset(radius, 5, radius))) {
            if (!mc.level.hasChunkAt(pos)) continue;
            BlockState block = mc.level.getBlockState(pos);
            if (block.isAir()) continue;
            String id = BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
            if (!(block.is(BlockTags.LOGS) || block.is(BlockTags.CROPS) || id.contains("ore") || id.contains("chest") || id.contains("crafting") || id.contains("furnace") || id.contains("farmland"))) continue;
            resources.add(pos.immutable());
        }
        resources.sort(Comparator.comparingDouble(pos -> mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos))));
        for (BlockPos pos : resources) {
            BlockState block = mc.level.getBlockState(pos); String id = BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
            // Sort before capping: a large field must not fill observations with the far corner.
            BlockHitResult hit = mc.level.clip(new ClipContext(mc.player.getEyePosition(), Vec3.atCenterOf(pos), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
            if (!hit.getBlockPos().equals(pos)) continue;
            JsonObject entry = new JsonObject(); entry.addProperty("id", id); entry.addProperty("x", pos.getX()); entry.addProperty("y", pos.getY()); entry.addProperty("z", pos.getZ()); entry.addProperty("state", block.toString()); blocks.add(entry);
            entry.addProperty("distance", Math.round(mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) * 10) / 10.0);
            entry.addProperty("reachable", visibleHit(pos) != null);
            if (block.getBlock() instanceof CropBlock crop) entry.addProperty("mature", crop.isMaxAge(block));
            if (blocks.size() >= 48) break;
        }
        state.add("visibleBlocks", blocks);
        JsonArray entities = new JsonArray();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || mc.player.distanceTo(entity) > radius * 2 || !visibleEntity(entity)) continue;
            JsonObject entry = new JsonObject(); entry.addProperty("name", entity.getName().getString()); entry.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            if (entity instanceof Display.ItemDisplay display) {
                ItemStack stack = ((dev.mcai.partner.mixin.motor.DisplayItemAccessor)display).wildling$item();
                JsonObject item = new JsonObject(); item.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()); item.addProperty("name", stack.getHoverName().getString());
                if (stack.has(DataComponents.CUSTOM_MODEL_DATA)) item.addProperty("customModelData", String.valueOf(stack.get(DataComponents.CUSTOM_MODEL_DATA)));
                entry.add("displayItem", item); addBackingBlock(entry, entity.blockPosition());
            }
            if (entity instanceof Display.BlockDisplay display) {
                BlockState displayed = ((dev.mcai.partner.mixin.motor.DisplayBlockAccessor)display).wildling$block();
                entry.addProperty("displayBlock", BuiltInRegistries.BLOCK.getKey(displayed.getBlock()).toString()); addBackingBlock(entry, entity.blockPosition());
            }
            entry.addProperty("distance", Math.round(mc.player.distanceTo(entity) * 10) / 10.0);
            entry.addProperty("hostile", entity instanceof Mob mob && hostile(mob));
            if (entity instanceof LivingEntity living) entry.addProperty("health", living.getHealth());
            entry.addProperty("x", entity.getX()); entry.addProperty("y", entity.getY()); entry.addProperty("z", entity.getZ()); entities.add(entry); if (entities.size() >= 24) break;
        }
        state.add("visibleEntities", entities); state.add("customCropTargets", cropTargets("", radius)); return state;
    }
    private void addBackingBlock(JsonObject entry, BlockPos position) {
        for (int dy = 0; dy >= -1; dy--) {
            BlockPos block = position.offset(0, dy, 0); if (visibleHitOrFar(block) == null) continue;
            BlockState state = mc.level.getBlockState(block); if (state.isAir()) continue;
            JsonObject backing = new JsonObject(); backing.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            backing.addProperty("x", block.getX()); backing.addProperty("y", block.getY()); backing.addProperty("z", block.getZ()); entry.add("backingBlock", backing); return;
        }
    }
    private boolean visibleEntity(Entity entity) {
        if (mc.player.hasLineOfSight(entity)) return true;
        if (!(entity instanceof Display display)) return false;
        Vec3 point = entity.position();
        if (display.renderState() != null) {
            var translation = display.renderState().transformation().get(1).translation();
            point = point.add(translation.x(), translation.y(), translation.z());
        }
        var hit = mc.level.clip(new ClipContext(mc.player.getEyePosition(), point.add(0, .25, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        // Custom crops can render inside the logical note-block proxy. Hitting that visible
        // backing surface is not the same as an unrelated opaque wall hiding the display.
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(entity.blockPosition()) || hit.getBlockPos().equals(entity.blockPosition().below());
    }
    private BlockHitResult visibleHitOrFar(BlockPos position) {
        if (!mc.level.hasChunkAt(position)) return null;
        BlockHitResult hit = mc.level.clip(new ClipContext(mc.player.getEyePosition(), Vec3.atCenterOf(position), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(position) ? hit : null;
    }
    public JsonArray cropTargets(String requestedItem, int radius) {
        JsonArray targets = new JsonArray(); if (mc.player == null || mc.level == null) return targets;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Display.ItemDisplay display) || mc.player.distanceTo(entity) > radius * 2 || !visibleEntity(entity)) continue;
            ItemStack stack = ((dev.mcai.partner.mixin.motor.DisplayItemAccessor)display).wildling$item();
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), name = stack.getHoverName().getString();
            String cropItem = id.equals("minecraft:wheat") || name.contains("小麦") ? "minecraft:wheat"
                    : id.equals("minecraft:carrot") || name.contains("胡萝卜") ? "minecraft:carrot"
                    : id.equals("minecraft:potato") || name.contains("土豆") || name.contains("马铃薯") ? "minecraft:potato" : "";
            if (cropItem.isEmpty() || !requestedItem.isEmpty() && !requestedItem.equals(cropItem)) continue;
            for (int dy = 0; dy >= -1; dy--) {
                BlockPos position = entity.blockPosition().offset(0, dy, 0); BlockState backing = mc.level.getBlockState(position);
                if (!(backing.getBlock() instanceof CropBlock) && !backing.is(Blocks.NOTE_BLOCK) && !backing.is(Blocks.TRIPWIRE)) continue;
                if (backing.isAir() || backing.hasBlockEntity() || hazard(backing) || !backing.getFluidState().isEmpty()) continue;
                if (backing.getBlock() instanceof CropBlock crop && !crop.isMaxAge(backing)) continue;
                if (!(backing.getBlock() instanceof CropBlock) && visibleHitOrFar(position) == null) continue;
                JsonObject target = new JsonObject(); target.addProperty("item", cropItem); target.addProperty("logicalBlock", BuiltInRegistries.BLOCK.getKey(backing.getBlock()).toString());
                target.addProperty("x", position.getX()); target.addProperty("y", position.getY()); target.addProperty("z", position.getZ()); targets.add(target); break;
            }
            if (targets.size() >= 32) break;
        }
        return targets;
    }
}
