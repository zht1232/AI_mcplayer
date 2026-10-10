package dev.mcai.partner;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;

/** Bounded vanilla workbench recipes. Every placement, menu opening and click goes to the server. */
public final class WorkbenchSkill {
    private enum Phase { PREPARE, PLACE, WAIT_TABLE, OPEN, WAIT_MENU, CRAFT, VERIFY }
    private record Click(int slot, int button) {}
    private final Minecraft mc;
    private final ClientMotor motor;
    private final ArrayDeque<Click> clicks = new ArrayDeque<>();
    private Phase phase;
    private String recipe, output, result;
    private BlockPos table, support;
    private CraftingMenu ownedMenu;
    private Screen ownedScreen, overlay;
    private String chatDraft;
    private int age, delay, stable, outputStart, tableStart, menuState, selected = -1, tableHotbar = -1, swappedSource = -1;

    public WorkbenchSkill(Minecraft mc, ClientMotor motor) { this.mc = mc; this.motor = motor; }
    public boolean busy() { return phase != null; }
    public boolean hasReachableTable() { return mc.player != null && mc.level != null && visibleTable() != null; }
    public String drainResult() { String value = result; result = null; return value; }

    public String start(String requested) {
        if (busy() || mc.player == null || mc.level == null || mc.gameMode == null || !mc.player.isAlive()
                || motor.busy() || ScreenPolicy.blocksWorld(mc.gui.screen())
                || mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.inventoryMenu.getCarried().isEmpty())
            return "FAILED: workbench crafting requires an idle player, free own inventory and empty cursor";
        if (!requested.equals("wooden_axe") && !requested.equals("wooden_pickaxe") && !requested.equals("wooden_sword"))
            return "FAILED: workbench recipe must be wooden_axe/wooden_pickaxe/wooden_sword";
        for (int slot = 1; slot <= 4; slot++) if (!mc.player.inventoryMenu.getSlot(slot).getItem().isEmpty())
            return "FAILED: finish the existing personal crafting grid first";
        int planks = requested.equals("wooden_sword") ? 2 : 3, sticks = requested.equals("wooden_sword") ? 1 : 2;
        if (inputSlot(9, 45, true, planks) < 0 || inputSlot(9, 45, false, sticks) < 0)
            return "FAILED: existing planks/sticks are insufficient for " + requested;
        if (emptySlot(9, 45) < 0) return "FAILED: reserve an empty inventory slot for the crafted tool";
        table = visibleTable(); support = null;
        if (table == null) {
            if (motor.inventoryCount("minecraft:crafting_table") == 0) return "FAILED: no reachable workbench or existing crafting-table item";
            support = safeSupport();
            if (support == null) return "FAILED: no visible safe natural support for the existing crafting table";
            table = support.above();
        }
        this.recipe = requested; output = "minecraft:" + requested; outputStart = motor.inventoryCount(output);
        tableStart = motor.inventoryCount("minecraft:crafting_table"); age = delay = stable = 0;
        ownedMenu = null; ownedScreen = null; overlay = null; chatDraft = null; result = null;
        selected = tableHotbar = swappedSource = -1; clicks.clear(); rememberOverlay();
        phase = support == null ? Phase.OPEN : Phase.PREPARE;
        return "STARTED: workbench crafting " + recipe + "; await server menu and stable inventory output";
    }

    private int inputSlot(int from, int to, boolean planks, int required) {
        var menu = mc.player.containerMenu;
        for (int slot = from; slot < to; slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if ((planks ? id.startsWith("minecraft:") && id.endsWith("_planks") : stack.is(Items.STICK)) && stack.getCount() >= required) return slot;
        }
        return -1;
    }
    private int emptySlot(int from, int to) {
        for (int slot = from; slot < to; slot++) if (mc.player.containerMenu.getSlot(slot).getItem().isEmpty()) return slot;
        return -1;
    }
    private boolean visible(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition(), point = Vec3.atCenterOf(pos);
        if (!mc.level.hasChunkAt(pos) || eye.distanceTo(point) > mc.player.blockInteractionRange() - .1) return false;
        var hit = mc.level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }
    private BlockPos visibleTable() {
        ArrayList<BlockPos> candidates = new ArrayList<>();
        BlockPos feet = mc.player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-4, -2, -4), feet.offset(4, 2, 4)))
            if (mc.level.getBlockState(pos).is(Blocks.CRAFTING_TABLE) && visible(pos)) candidates.add(pos.immutable());
        return candidates.stream().min(Comparator.comparingDouble(pos -> mc.player.position().distanceToSqr(Vec3.atCenterOf(pos)))).orElse(null);
    }
    private boolean naturalSupport(BlockState state) {
        return state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE) || state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.ANDESITE)
                || state.is(Blocks.DIORITE) || state.is(Blocks.GRANITE) || state.is(Blocks.TUFF)
                || state.is(Blocks.DEEPSLATE) || state.is(Blocks.COBBLED_DEEPSLATE);
    }
    private boolean dangerous(BlockState state) {
        return !state.getFluidState().isEmpty() || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.SWEET_BERRY_BUSH);
    }
    private BlockPos safeSupport() {
        ArrayList<BlockPos> candidates = new ArrayList<>(); BlockPos feet = mc.player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-2, -2, -2), feet.offset(2, 0, 2))) {
            if (!mc.level.hasChunkAt(pos) || !naturalSupport(mc.level.getBlockState(pos))
                    || !mc.level.getBlockState(pos).isFaceSturdy(mc.level, pos, Direction.UP)) continue;
            BlockPos above = pos.above();
            if (!mc.level.getBlockState(above).isAir() || !mc.level.getBlockState(above.above()).isAir()
                    || !mc.level.noCollision(null, new AABB(above))) continue;
            if (mc.player.getBoundingBox().intersects(new AABB(above))) continue;
            boolean safe = true;
            for (BlockPos near : BlockPos.betweenClosed(above.offset(-1, -1, -1), above.offset(1, 1, 1)))
                if (dangerous(mc.level.getBlockState(near))) { safe = false; break; }
            if (!safe) continue;
            // Trace just inside the supporting face; ending outside the face produces a MISS.
            Vec3 point = Vec3.atCenterOf(pos).add(0, .499, 0);
            if (mc.player.getEyePosition().distanceTo(point) > mc.player.blockInteractionRange() - .1) continue;
            var hit = mc.level.clip(new ClipContext(mc.player.getEyePosition(), point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) && hit.getDirection() == Direction.UP) candidates.add(pos.immutable());
        }
        return candidates.stream().min(Comparator.comparingDouble(pos -> mc.player.position().distanceToSqr(Vec3.atCenterOf(pos)))).orElse(null);
    }
    private void click(int slot, int button, ContainerInput input) {
        mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slot, button, input, mc.player);
    }
    private String placeTable() {
        Vec3 point = Vec3.atCenterOf(support).add(0, .499, 0), eye = mc.player.getEyePosition();
        var hit = mc.level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(support) || hit.getDirection() != Direction.UP)
            return "FAILED: the safe supporting face is no longer visible";
        Vec3 delta = point.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        mc.player.setYRot(mc.player.getYRot() + Math.clamp(net.minecraft.util.Mth.wrapDegrees(yaw - mc.player.getYRot()), -15f, 15f));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z))));
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit); mc.player.swing(InteractionHand.MAIN_HAND);
        return "SENT: normal existing-table placement";
    }
    private void rememberOverlay() {
        Screen current = mc.gui.screen();
        if (current instanceof ChatScreen || current instanceof PauseScreen) {
            overlay = current;
            if (current instanceof ChatScreen) for (var widget : current.children()) if (widget instanceof EditBox box) { chatDraft = box.getValue(); break; }
        }
    }

    public void tick() {
        if (!busy()) return;
        if (mc.player == null || mc.level == null || mc.gameMode == null || !mc.player.isAlive()) { finish("FAILED: workbench player disconnected or died"); return; }
        rememberOverlay();
        if (++age > 400) { finish("FAILED: server did not confirm workbench recipe within 20 seconds"); return; }
        if (motor.busy()) { finish("FAILED: workbench interrupted by a higher-priority physical action"); return; }
        if (ownedMenu != null && mc.player.containerMenu != ownedMenu) { finish("FAILED: workbench menu was replaced or closed"); return; }
        if (ownedMenu == null && phase != Phase.WAIT_MENU && (mc.player.containerMenu != mc.player.inventoryMenu || ScreenPolicy.blocksWorld(mc.gui.screen()))) {
            finish("FAILED: owner opened another interface before the workbench"); return;
        }
        Screen current = mc.gui.screen();
        if (ownedMenu != null && current != null && current != ownedScreen && !(current instanceof ChatScreen) && !(current instanceof PauseScreen)) {
            finish("FAILED: owner opened another interface while crafting"); return;
        }
        if (--delay > 0) return; delay = 4;
        try {
            switch (phase) {
                case PREPARE -> {
                    selected = mc.player.getInventory().getSelectedSlot();
                    for (int slot = 0; slot < 9; slot++) if (mc.player.getInventory().getItem(slot).is(Items.CRAFTING_TABLE)) { tableHotbar = slot; break; }
                    if (tableHotbar < 0) {
                        tableHotbar = 8;
                        for (int slot = 0; slot < 9; slot++) if (mc.player.getInventory().getItem(slot).isEmpty()) { tableHotbar = slot; break; }
                        for (int slot = 9; slot < 36; slot++) if (mc.player.getInventory().getItem(slot).is(Items.CRAFTING_TABLE)) { swappedSource = slot; break; }
                        if (swappedSource < 0) { finish("FAILED: crafting-table item changed before placement"); return; }
                        click(swappedSource, tableHotbar, ContainerInput.SWAP);
                    }
                    phase = Phase.PLACE; delay = 8;
                }
                case PLACE -> {
                    if (!mc.player.getInventory().getItem(tableHotbar).is(Items.CRAFTING_TABLE)) return;
                    mc.player.getInventory().setSelectedSlot(tableHotbar);
                    if (!mc.level.getBlockState(table).isAir() || !naturalSupport(mc.level.getBlockState(support))) { finish("FAILED: placement space changed"); return; }
                    String placed = placeTable();
                    if (!placed.startsWith("SENT")) { finish(placed); return; }
                    phase = Phase.WAIT_TABLE; stable = 0;
                }
                case WAIT_TABLE -> {
                    if (mc.level.getBlockState(table).is(Blocks.CRAFTING_TABLE) && motor.inventoryCount("minecraft:crafting_table") < tableStart) stable += 4; else stable = 0;
                    if (stable >= 12) { restoreSelection(); phase = Phase.OPEN; }
                }
                case OPEN -> {
                    if (mc.player.containerMenu != mc.player.inventoryMenu) { finish("FAILED: another container opened before the workbench"); return; }
                    String opened = motor.useBlock(table);
                    if (!opened.startsWith("SENT")) { finish(opened); return; }
                    phase = Phase.WAIT_MENU;
                }
                case WAIT_MENU -> {
                    if (mc.player.containerMenu == mc.player.inventoryMenu) return;
                    if (!(mc.player.containerMenu instanceof CraftingMenu menu)) { finish("FAILED: server opened a different menu; leaving it untouched"); return; }
                    ownedMenu = menu; ownedScreen = mc.gui.screen(); menuState = menu.getStateId();
                    if (!prepareRecipe()) { finish("FAILED: workbench grid, cursor or recipe inputs are unavailable"); return; }
                    phase = Phase.CRAFT;
                }
                case CRAFT -> {
                    if (clicks.isEmpty()) { phase = Phase.VERIFY; stable = 0; return; }
                    Click next = clicks.peekFirst();
                    if (next.slot == 0 && !BuiltInRegistries.ITEM.getKey(ownedMenu.getSlot(0).getItem().getItem()).toString().equals(output)) return;
                    clicks.removeFirst(); click(next.slot, next.button, ContainerInput.PICKUP);
                }
                case VERIFY -> {
                    boolean ready = motor.inventoryCount(output) >= outputStart + 1 && ownedMenu.getStateId() != menuState && ownedMenu.getCarried().isEmpty() && gridEmpty();
                    stable = ready ? stable + 4 : 0;
                    if (stable >= 20) finish("OK: server workbench output stabilized in inventory: " + output);
                }
            }
        } catch (RuntimeException exception) { finish("FAILED: workbench action rejected: " + exception.getMessage()); }
    }

    private boolean prepareRecipe() {
        if (!ownedMenu.getCarried().isEmpty() || !gridEmpty()) return false;
        int[] wood = recipe.equals("wooden_axe") ? new int[]{1, 2, 4} : recipe.equals("wooden_pickaxe") ? new int[]{1, 2, 3} : new int[]{2, 5};
        int[] sticks = recipe.equals("wooden_sword") ? new int[]{8} : new int[]{5, 8};
        int plankSlot = inputSlot(10, 46, true, wood.length), stickSlot = inputSlot(10, 46, false, sticks.length), destination = emptySlot(10, 46);
        if (plankSlot < 0 || stickSlot < 0 || destination < 0) return false;
        clicks.add(new Click(plankSlot, 0)); for (int slot : wood) clicks.add(new Click(slot, 1)); clicks.add(new Click(plankSlot, 0));
        clicks.add(new Click(stickSlot, 0)); for (int slot : sticks) clicks.add(new Click(slot, 1)); clicks.add(new Click(stickSlot, 0));
        clicks.add(new Click(0, 0)); clicks.add(new Click(destination, 0)); return true;
    }
    private boolean gridEmpty() {
        for (int slot = 1; slot <= 9; slot++) if (!ownedMenu.getSlot(slot).getItem().isEmpty()) return false;
        return true;
    }
    private void restoreSelection() {
        if (selected < 0 || mc.player == null) return;
        if (swappedSource >= 0 && mc.gameMode != null && mc.player.containerMenu == mc.player.inventoryMenu)
            click(swappedSource, tableHotbar, ContainerInput.SWAP);
        mc.player.getInventory().setSelectedSlot(selected); selected = swappedSource = -1;
    }
    private boolean cleanupMenu() {
        if (ownedMenu == null || mc.player == null || mc.gameMode == null || mc.player.containerMenu != ownedMenu) return true;
        if (!ownedMenu.getCarried().isEmpty()) {
            for (int slot = 10; slot < 46; slot++) {
                ItemStack item = ownedMenu.getSlot(slot).getItem(), carried = ownedMenu.getCarried();
                if (item.isEmpty() || ItemStack.isSameItemSameComponents(item, carried) && item.getCount() + carried.getCount() <= item.getMaxStackSize()) { click(slot, 0, ContainerInput.PICKUP); break; }
            }
        }
        if (!ownedMenu.getCarried().isEmpty()) return false;
        for (int slot = 1; slot <= 9; slot++) if (!ownedMenu.getSlot(slot).getItem().isEmpty()) click(slot, 0, ContainerInput.QUICK_MOVE);
        if (!gridEmpty()) return false;
        Screen current = mc.gui.screen();
        // A separate owner screen must not be closed by this background skill.
        if (current != null && current != ownedScreen && !(current instanceof ChatScreen) && !(current instanceof PauseScreen)) return true;
        mc.player.closeContainer();
        if (overlay != null) {
            mc.gui.setScreen(overlay);
            if (overlay instanceof ChatScreen && chatDraft != null) for (var widget : overlay.children()) if (widget instanceof EditBox box) { box.setValue(chatDraft); break; }
        }
        return true;
    }
    private void finish(String message) {
        restoreSelection();
        if (!cleanupMenu()) message = "FAILED: inventory full; workbench cursor/grid retained without dropping items";
        result = message; phase = null; clicks.clear(); ownedMenu = null; ownedScreen = overlay = null; chatDraft = null;
    }
    public void cancel() { if (busy()) { rememberOverlay(); finish("FAILED: workbench crafting cancelled; existing ingredients returned where space permits"); } }
}
