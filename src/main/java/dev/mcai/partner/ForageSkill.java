package dev.mcai.partner;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Continuous, bounded food gathering through vanilla movement and interaction packets. */
public final class ForageSkill {
    private enum Kind { DROP, BERRY, ANIMAL }
    private enum Phase { APPROACH, INTERACT, WAIT_DROPS, PICKUP }
    private final Minecraft mc;
    private final ClientMotor motor;
    private final Map<String, Long> retryAfter = new HashMap<>();
    private Kind kind;
    private Phase phase;
    private UUID entity;
    private BlockPos bush;
    private Vec3 origin;
    private String food, key, result;
    private int countStart, age, delay, waitDrops, stable, previousSlot = -1;
    private boolean ownsMovement;

    public ForageSkill(Minecraft mc, ClientMotor motor) { this.mc = mc; this.motor = motor; }
    public boolean busy() { return phase != null; }
    public String drainResult() { String value = result; result = null; return value; }
    public void cancel() { if (busy()) finish("FAILED: food gathering cancelled"); }

    public String start() {
        if (busy() || mc.player == null || mc.level == null || mc.gameMode == null || !mc.player.isAlive()
                || motor.busy() || ScreenPolicy.blocksWorld(mc.gui.screen())
                || mc.player.containerMenu != mc.player.inventoryMenu)
            return "FAILED: forage requires an idle player with own inventory available";
        if (mc.player.getHealth() <= 8) return "FAILED: health too low to forage; recover or ask nearby players for help";
        result = null; age = delay = waitDrops = stable = 0; previousSlot = -1; ownsMovement = false;
        origin = mc.player.position();
        ArrayList<ItemEntity> drops = new ArrayList<>();
        ArrayList<Animal> animals = new ArrayList<>();
        for (Entity seen : mc.level.entitiesForRendering()) {
            if (!nearVisible(seen)) continue;
            if (seen instanceof ItemEntity item && safeFood(item.getItem()) && available(seen.getUUID().toString())) drops.add(item);
            if (seen instanceof Animal animal && prey(animal) && available(seen.getUUID().toString())) animals.add(animal);
        }
        drops.sort(Comparator.comparingDouble(item -> mc.player.distanceToSqr(item)));
        for (ItemEntity drop : drops) {
            if (approach(drop.blockPosition(), true)) {
                select(Kind.DROP, drop.getUUID(), null, BuiltInRegistries.ITEM.getKey(drop.getItem().getItem()).toString());
                return "STARTED: walking to visible dropped food and verifying inventory pickup";
            }
            cool(drop.getUUID().toString());
        }
        ArrayList<BlockPos> berries = new ArrayList<>();
        BlockPos feet = mc.player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-10, -3, -10), feet.offset(10, 3, 10))) {
            if (!mc.level.hasChunkAt(pos) || !available(pos.toShortString()) || mc.player.position().distanceTo(Vec3.atCenterOf(pos)) > 10) continue;
            var state = mc.level.getBlockState(pos);
            if (state.is(Blocks.SWEET_BERRY_BUSH) && state.getValue(SweetBerryBushBlock.AGE) >= 2 && visibleBush(pos)) berries.add(pos.immutable());
        }
        berries.sort(Comparator.comparingDouble(pos -> mc.player.position().distanceToSqr(Vec3.atCenterOf(pos))));
        for (BlockPos berry : berries) {
            if (motor.canMineNow(berry) || approach(berry, false)) {
                select(Kind.BERRY, null, berry, "minecraft:sweet_berries");
                if (!ownsMovement) phase = Phase.INTERACT;
                return "STARTED: approaching a mature berry bush, harvesting normally and collecting food";
            }
            cool(berry.toShortString());
        }
        animals.sort(Comparator.comparingDouble(animal -> mc.player.distanceToSqr(animal) + (animal instanceof Chicken ? 25 : 0)));
        for (Animal animal : animals) {
            if (mc.player.isWithinEntityInteractionRange(animal, 0) || approach(animal.blockPosition(), false)) {
                select(Kind.ANIMAL, animal.getUUID(), null, meat(animal));
                chooseWeapon(); delay = 8;
                if (!ownsMovement) phase = Phase.INTERACT;
                return "STARTED: hunting one visible adult food animal with normal melee, then verifying meat pickup";
            }
            cool(animal.getUUID().toString());
        }
        return "FAILED: no reachable visible safe food, mature berry bush or eligible adult food animal within 10 blocks";
    }

    private void select(Kind selected, UUID id, BlockPos block, String item) {
        kind = selected; entity = id; bush = block; food = item;
        key = id == null ? block.toShortString() : id.toString();
        countStart = motor.inventoryCount(food); phase = Phase.APPROACH;
    }
    private boolean available(String candidate) { return retryAfter.getOrDefault(candidate, 0L) <= System.currentTimeMillis(); }
    private void cool(String failed) {
        retryAfter.put(failed, System.currentTimeMillis() + 60_000);
        if (retryAfter.size() > 64) retryAfter.remove(retryAfter.keySet().iterator().next());
    }
    private boolean safeFood(ItemStack stack) {
        return stack.has(DataComponents.FOOD) && !stack.is(Items.ROTTEN_FLESH) && !stack.is(Items.PUFFERFISH)
                && !stack.is(Items.SPIDER_EYE) && !stack.is(Items.POISONOUS_POTATO)
                && !stack.is(Items.CHICKEN) && !stack.is(Items.SUSPICIOUS_STEW);
    }
    private boolean prey(Animal animal) {
        return (animal instanceof Cow || animal instanceof Pig || animal instanceof Sheep || animal instanceof Chicken)
                && animal.isAlive() && !animal.isBaby() && !animal.isLeashed() && !animal.hasCustomName()
                && !animal.isPassenger() && !animal.isVehicle() && animal.getItemBySlot(EquipmentSlot.SADDLE).isEmpty();
    }
    private String meat(Animal animal) {
        return animal instanceof Cow ? "minecraft:beef" : animal instanceof Pig ? "minecraft:porkchop"
                : animal instanceof Sheep ? "minecraft:mutton" : "minecraft:chicken";
    }
    private boolean nearVisible(Entity seen) {
        return seen.isAlive() && mc.level.hasChunkAt(seen.blockPosition())
                && origin.distanceTo(seen.position()) <= 10 && mc.player.distanceTo(seen) <= 10 && mc.player.hasLineOfSight(seen);
    }
    private Entity target() {
        if (entity == null) return null;
        for (Entity seen : mc.level.entitiesForRendering()) if (entity.equals(seen.getUUID())) return seen;
        return null;
    }
    private boolean visibleBush(BlockPos pos) {
        var hit = mc.level.clip(new ClipContext(mc.player.getEyePosition(), Vec3.atCenterOf(pos), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }
    private boolean approach(BlockPos pos, boolean drop) {
        ArrayList<BlockPos> candidates = new ArrayList<>();
        if (drop) candidates.add(pos);
        for (Direction side : Direction.Plane.HORIZONTAL) candidates.add(pos.relative(side));
        candidates.sort(Comparator.comparingDouble(standing -> mc.player.position().distanceToSqr(Vec3.atCenterOf(standing))));
        for (BlockPos standing : candidates) {
            if (!mc.level.hasChunkAt(standing) || origin.distanceTo(Vec3.atCenterOf(standing)) > 10.5) continue;
            String moved = motor.moveTo(standing);
            if (moved.startsWith("STARTED") || moved.startsWith("OK")) { ownsMovement = moved.startsWith("STARTED"); return true; }
        }
        return false;
    }
    private void chooseWeapon() {
        previousSlot = mc.player.getInventory().getSelectedSlot();
        int best = -1; double bestDamage = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!stack.is(ItemTags.SWORDS) && !stack.is(ItemTags.AXES)) continue;
            var modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
            double damage = modifiers == null ? 1 : modifiers.compute(Attributes.ATTACK_DAMAGE, 1, EquipmentSlot.MAINHAND);
            if (damage > bestDamage) { bestDamage = damage; best = slot; }
        }
        if (best >= 9) {
            int hotbar = 8;
            for (int slot = 0; slot < 9; slot++) if (mc.player.getInventory().getItem(slot).isEmpty()) { hotbar = slot; break; }
            mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, best, hotbar, ContainerInput.SWAP, mc.player);
            best = hotbar;
        }
        if (best >= 0) mc.player.getInventory().setSelectedSlot(best);
    }
    private boolean aim(Vec3 point) {
        Vec3 delta = point.subtract(mc.player.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)));
        mc.player.setYRot(mc.player.getYRot() + Math.clamp(Mth.wrapDegrees(yaw - mc.player.getYRot()), -15f, 15f));
        mc.player.setXRot(mc.player.getXRot() + Math.clamp(pitch - mc.player.getXRot(), -15f, 15f));
        return Math.abs(Mth.wrapDegrees(yaw - mc.player.getYRot())) < 15 && Math.abs(pitch - mc.player.getXRot()) < 15;
    }

    public void tick() {
        if (!busy()) return;
        if (mc.player == null || mc.level == null || mc.gameMode == null || !mc.player.isAlive()) { finish("FAILED: food gathering interrupted by death/disconnect"); return; }
        if (motor.defending() || motor.survivalBusy() || mc.player.getHealth() <= 8) { finish("FAILED: food gathering yielded to immediate survival or low health"); return; }
        if (ScreenPolicy.blocksWorld(mc.gui.screen()) || mc.player.containerMenu != mc.player.inventoryMenu) { finish("FAILED: food gathering interrupted by an interactive container"); return; }
        if (++age > 400) { finish("FAILED: food gathering timed out without a verified inventory increase"); return; }
        if (motor.inventoryCount(food) > countStart) {
            if (++stable >= 3) finish("OK: server-synchronised food inventory increased: " + food + (food.equals("minecraft:chicken") ? "; cook raw chicken before eating" : ""));
            return;
        }
        stable = 0;
        if (motor.busy()) {
            if (!ownsMovement) finish("FAILED: food gathering interrupted by another physical task");
            return;
        }
        ownsMovement = false;
        if (delay-- > 0) return;
        Entity seen = target();
        switch (phase) {
            case APPROACH -> phase = kind == Kind.DROP ? Phase.PICKUP : Phase.INTERACT;
            case INTERACT -> {
                if (kind == Kind.BERRY) {
                    if (!mc.level.hasChunkAt(bush) || !mc.level.getBlockState(bush).is(Blocks.SWEET_BERRY_BUSH)
                            || mc.level.getBlockState(bush).getValue(SweetBerryBushBlock.AGE) < 2) {
                        finish("FAILED: berry bush is no longer mature; no food inventory increase confirmed"); return;
                    }
                    if (!motor.canMineNow(bush)) {
                        if (!approach(bush, false)) { finish("FAILED: cannot safely reach the visible berry bush"); return; }
                        phase = Phase.APPROACH; return;
                    }
                    // Bone meal has a different right-click action; select a harmless existing slot.
                    if (mc.player.getMainHandItem().is(Items.BONE_MEAL)) {
                        if (previousSlot < 0) previousSlot = mc.player.getInventory().getSelectedSlot();
                        for (int slot = 0; slot < 9; slot++) if (!mc.player.getInventory().getItem(slot).is(Items.BONE_MEAL)) { mc.player.getInventory().setSelectedSlot(slot); break; }
                        if (mc.player.getMainHandItem().is(Items.BONE_MEAL)) { finish("FAILED: no harmless hotbar slot for berry harvesting"); return; }
                    }
                    if (!aim(Vec3.atCenterOf(bush))) return;
                    String used = motor.useBlock(bush);
                    if (!used.startsWith("SENT")) { finish(used); return; }
                    phase = Phase.WAIT_DROPS; waitDrops = 0;
                } else {
                    if (seen == null || !seen.isAlive()) { phase = Phase.WAIT_DROPS; waitDrops = 0; return; }
                    if (!(seen instanceof Animal animal) || !prey(animal) || !nearVisible(seen)) { finish("FAILED: food animal is no longer an eligible nearby visible target"); return; }
                    if (!mc.player.isWithinEntityInteractionRange(seen, 0)) {
                        if (!approach(seen.blockPosition(), false)) { finish("FAILED: cannot safely approach the moving food animal"); return; }
                        phase = Phase.APPROACH; return;
                    }
                    if (aim(seen.getEyePosition()) && mc.player.getAttackStrengthScale(0) >= .9f) {
                        mc.gameMode.attack(mc.player, seen); mc.player.swing(InteractionHand.MAIN_HAND);
                    }
                }
            }
            case WAIT_DROPS -> {
                if (++waitDrops < 10) return;
                ItemEntity foodDrop = droppedTargetFood();
                if (foodDrop != null && approach(foodDrop.blockPosition(), true)) { entity = foodDrop.getUUID(); phase = Phase.PICKUP; delay = 5; }
                else if (waitDrops >= 80) finish("FAILED: no target food pickup confirmed after normal harvest/hunt");
            }
            case PICKUP -> {
                if (++waitDrops >= 100) { finish("FAILED: dropped food did not increase server-synchronised inventory"); return; }
                if (seen == null || !nearVisible(seen)) return; // Disappearance alone is never counted as pickup.
                if (waitDrops % 10 == 0 && !approach(seen.blockPosition(), true)) finish("FAILED: visible dropped food has no safe pickup route");
            }
        }
    }
    private ItemEntity droppedTargetFood() {
        ItemEntity nearest = null; double distance = Double.MAX_VALUE;
        for (Entity seen : mc.level.entitiesForRendering()) if (seen instanceof ItemEntity item && nearVisible(item)
                && BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString().equals(food)) {
            double candidate = mc.player.distanceToSqr(item);
            if (candidate < distance) { nearest = item; distance = candidate; }
        }
        return nearest;
    }
    private void finish(String outcome) {
        if (outcome.startsWith("FAILED") && key != null) cool(key);
        if (ownsMovement && !motor.survivalBusy()) motor.stop();
        if (previousSlot >= 0 && mc.player != null && !motor.survivalBusy()) mc.player.getInventory().setSelectedSlot(previousSlot);
        result = outcome; phase = null; kind = null; entity = null; bush = null;
        ownsMovement = false; previousSlot = -1;
    }
}
