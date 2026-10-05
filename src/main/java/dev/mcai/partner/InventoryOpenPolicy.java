package dev.mcai.partner;

/** Inventory snapshots are passive; opening the screen is an operation, not an observation. */
public final class InventoryOpenPolicy {
    private long lastOpened = Long.MIN_VALUE;
    public String rejection(String purpose, long tick, boolean explicitTask, boolean nearFull) {
        if (!java.util.Set.of("craft", "organize", "equip").contains(purpose == null ? "" : purpose))
            return "SKIPPED: inventory is already visible in observations; use observe(inventory) for counts. Open only for craft/organize/equip.";
        if (purpose.equals("organize") && !explicitTask && !nearFull)
            return "SKIPPED: inventory is not full and no organization task was requested; continue collecting.";
        if (!explicitTask && lastOpened != Long.MIN_VALUE && tick - lastOpened < 1200)
            return "SKIPPED: do not reopen inventory after each block; continue the physical skill and read current counts.";
        return null;
    }
    public void opened(long tick) { lastOpened = tick; }
    public void reset() { lastOpened = Long.MIN_VALUE; }
}
