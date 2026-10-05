package dev.mcai.partner;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.ArrayDeque;

/** Small vanilla 2x2 recipes with ordinary server-validated inventory clicks, no screen opening. */
public final class BasicCraftSkill {
    private record Click(int slot, int button) {}
    private final Minecraft mc;
    private final ArrayDeque<Click> clicks = new ArrayDeque<>();
    private String output, result;
    private int outputStart, expected, delay, age, stable;
    public BasicCraftSkill(Minecraft mc) { this.mc = mc; }
    public boolean busy() { return output != null; }
    private int count(String id) {
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            var stack = mc.player.getInventory().getItem(slot);
            if (BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(id)) total += stack.getCount();
        }
        return total;
    }
    public String start(String recipe) {
        if (busy() || mc.player == null || mc.gameMode == null || mc.player.containerMenu != mc.player.inventoryMenu
                || !mc.player.inventoryMenu.getCarried().isEmpty()) return "FAILED: crafting requires the free own inventory and an empty cursor";
        for (int slot = 1; slot <= 4; slot++) if (!mc.player.inventoryMenu.getSlot(slot).getItem().isEmpty()) return "FAILED: finish the existing crafting-grid contents first";
        int source = -1, destination = -1, required; int[] grid;
        if (recipe.equals("planks")) { required = 1; grid = new int[]{1}; expected = 4; }
        else if (recipe.equals("sticks")) { required = 2; grid = new int[]{1,3}; expected = 4; }
        else if (recipe.equals("crafting_table")) { required = 4; grid = new int[]{1,2,3,4}; expected = 1; }
        else return "FAILED: basic recipe must be planks/sticks/crafting_table";
        String input = null;
        for (int slot = 9; slot < 45; slot++) {
            var item = mc.player.inventoryMenu.getSlot(slot).getItem(); String id = BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
            boolean matches = recipe.equals("planks") ? id.matches("minecraft:(oak|spruce|birch|jungle|acacia|dark_oak|mangrove|cherry|pale_oak)_log") : id.endsWith("_planks");
            if (matches && item.getCount() >= required) { source = slot; input = id; break; }
        }
        if (source < 0) return "FAILED: recipe inputs are missing";
        String product = recipe.equals("planks") ? input.replace("_log", "_planks") : "minecraft:" + (recipe.equals("sticks") ? "stick" : "crafting_table");
        for (int slot = 9; slot < 45; slot++) {
            var item = mc.player.inventoryMenu.getSlot(slot).getItem();
            if (item.isEmpty() || BuiltInRegistries.ITEM.getKey(item.getItem()).toString().equals(product) && item.getCount() + expected <= item.getMaxStackSize()) { destination = slot; break; }
        }
        if (destination < 0) return "FAILED: no inventory destination for crafted output";
        output = product; outputStart = count(product); result = null; age = 0; delay = 0; stable = 0;
        clicks.add(new Click(source,0)); for (int cell : grid) clicks.add(new Click(cell,1));
        clicks.add(new Click(source,0)); clicks.add(new Click(0,0)); clicks.add(new Click(destination,0));
        return "STARTED: basic crafting " + recipe + "; wait for server inventory output";
    }
    public void tick() {
        if (!busy()) return;
        if (mc.player == null || !mc.player.isAlive() || mc.player.containerMenu != mc.player.inventoryMenu) { finish("FAILED: basic crafting interrupted by player/menu state"); return; }
        if (++age > 160) { finish("FAILED: server did not confirm basic recipe output"); return; }
        if (--delay > 0) return; delay = 4;
        if (!clicks.isEmpty()) {
            Click action = clicks.removeFirst();
            if (action.slot == 0 && mc.player.inventoryMenu.getSlot(0).getItem().isEmpty()) { clicks.addFirst(action); return; }
            mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, action.slot, action.button, ContainerInput.PICKUP, mc.player);
        } else {
            stable = count(output) >= outputStart + expected && mc.player.inventoryMenu.getCarried().isEmpty() ? stable + 4 : 0;
            if (stable >= 20) finish("OK: inventory stabilized after normal server crafting clicks: " + output);
        }
    }
    private void cleanup() {
        if (mc.player == null || mc.gameMode == null || mc.player.containerMenu != mc.player.inventoryMenu) return;
        var menu = mc.player.inventoryMenu;
        if (!menu.getCarried().isEmpty()) for (int slot = 9; slot < 45; slot++) {
            var item = menu.getSlot(slot).getItem();
            if (item.isEmpty() || net.minecraft.world.item.ItemStack.isSameItemSameComponents(item, menu.getCarried()) && item.getCount() < item.getMaxStackSize()) {
                mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, mc.player); break;
            }
        }
        if (menu.getCarried().isEmpty()) for (int slot = 1; slot <= 4; slot++) if (!menu.getSlot(slot).getItem().isEmpty())
            mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.QUICK_MOVE, mc.player);
    }
    private void finish(String message) { if (message.startsWith("FAILED")) cleanup(); result = message; output = null; clicks.clear(); }
    public String drainResult() { String value = result; result = null; return value; }
    public void cancel() { if (busy()) finish("FAILED: basic crafting interrupted; inspect cursor/grid before more inventory actions"); }
}
