package dev.mcai.partner;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** Opt-in real-time generated-world run. No mocked model, gifts, healing or time acceleration. */
final class NaturalSurvivalSoak {
    private static final long SEED = 20261005L;
    static int minutes() {
        String value = System.getenv("WILDLING_SOAK_MINUTES");
        return value == null ? 0 : Math.clamp(Integer.parseInt(value), 0, 120);
    }

    static void run(ClientGameTestContext context) {
        int minutes = minutes();
        Path reports = Path.of(System.getenv().getOrDefault("WILDLING_SOAK_REPORT_DIR", "soak-reports")).toAbsolutePath();
        RunStats stats = new RunStats(minutes);
        Throwable failure = null;
        Properties properties = new Properties();
        properties.setProperty("online-mode", "false"); properties.setProperty("spawn-protection", "0");
        properties.setProperty("enforce-secure-profile", "false"); properties.setProperty("difficulty", "normal");
        properties.setProperty("view-distance", "6"); properties.setProperty("gamemode", "survival");
        try {
            Files.createDirectories(reports);
            Path timeline = reports.resolve("timeline.jsonl"); Files.writeString(timeline, "", StandardCharsets.UTF_8);
            // The builder supplies level data; level-seed in server.properties does not reliably override it.
            try (TestDedicatedServerContext server = context.worldBuilder().setUseConsistentSettings(false)
                    .adjustSettings(state -> {
                        state.setSeed(Long.toString(SEED));
                        state.setGameMode(net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.SelectedGameMode.SURVIVAL);
                        state.setDifficulty(net.minecraft.world.Difficulty.NORMAL);
                        state.setBonusChest(false);
                    }).createServer(properties);
                 TestDedicatedServerConnection connection = server.connect()) {
                connection.waitForChunksDownload();
                stats.actualSeed = server.computeOnServer(mcServer -> mcServer.overworld().getSeed());
                if (stats.actualSeed != SEED) throw new AssertionError("Generated seed differs: requested " + SEED + ", actual " + stats.actualSeed);
                server.runOnServer(mcServer -> {
                    if (connection.getServerPlayer().gameMode.getGameModeForPlayer() != net.minecraft.world.level.GameType.SURVIVAL)
                        throw new AssertionError("Natural-world player must use survival mode");
                    if (mcServer.overworld().getDifficulty() != net.minecraft.world.Difficulty.NORMAL)
                        throw new AssertionError("Natural-world difficulty must be normal");
                });
                context.runOnClient(mc -> {
                    var partner = PartnerClient.instance(); partner.config().baseUrl = "http://127.0.0.1:8080/v1";
                    partner.config().model = "Qwen3.5-4B"; partner.config().apiKey = ""; partner.config().apiKeyEnv = "";
                    partner.config().enableThinking = false; partner.config().reasoningProtocol = "llama";
                    if (partner.command("enable", "") != 1) throw new AssertionError("Natural-world activation failed");
                });
                stats.start = System.currentTimeMillis();
                long nextReport = stats.start;
                try {
                    while (System.currentTimeMillis() - stats.start < minutes * 60_000L) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Natural survival run interrupted");
                        context.waitTicks(20);
                        if (!context.computeOnClient(mc -> mc.player != null && mc.level != null)) {
                            throw new AssertionError("Client disconnected during natural survival run");
                        }
                        // One-second health minima include damage hidden between the 30-second snapshots.
                        server.runOnServer(mcServer -> {
                            var player = connection.getServerPlayer();
                            stats.minHealth = Math.min(stats.minHealth, player.getHealth());
                            stats.minFood = Math.min(stats.minFood, player.getFoodData().getFoodLevel());
                            stats.died |= !player.isAlive();
                        });
                        if (System.currentTimeMillis() >= nextReport || stats.died) {
                            long elapsedMillis = System.currentTimeMillis() - stats.start;
                            JsonObject sample = context.computeOnClient(mc -> {
                                var partner = PartnerClient.instance(); JsonObject data = new JsonObject();
                                data.addProperty("elapsedSeconds", elapsedMillis / 1000.0);
                                data.addProperty("x", mc.player.getX()); data.addProperty("y", mc.player.getY()); data.addProperty("z", mc.player.getZ());
                                data.addProperty("health", mc.player.getHealth()); data.addProperty("food", mc.player.getFoodData().getFoodLevel());
                                data.addProperty("executionActive", partner.executionActive());
                                data.addProperty("worldTicks", mc.level.getGameTime()); data.addProperty("dayTime", mc.level.getOverworldClockTime());
                                data.add("nearby", partner.motor().snapshot(8));
                                data.addProperty("status", partner.status()); data.addProperty("modelMetrics", partner.plannerSummary());
                                data.addProperty("lastError", partner.lastError());
                                data.add("inventory", counts(mc.player.getInventory())); return data;
                            });
                            JsonObject authoritative = server.computeOnServer(mcServer -> {
                                var player = connection.getServerPlayer(); JsonObject data = new JsonObject();
                                data.addProperty("worldTicks", mcServer.overworld().getGameTime());
                                data.addProperty("dayTime", mcServer.overworld().getOverworldClockTime());
                                data.addProperty("health", player.getHealth()); data.addProperty("food", player.getFoodData().getFoodLevel());
                                data.add("inventory", counts(player.getInventory())); return data;
                            });
                            sample.add("server", authoritative);
                            stats.add(sample, elapsedMillis);
                            Files.writeString(timeline, sample + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                            // Save partial evidence as well: a process kill/power loss cannot execute finally.
                            saveResult(reports, stats, false, null);
                            System.out.printf("WILDLING_SOAK elapsed=%.0fs health=%.1f food=%d ticks=%d resourceGain=%d craftedEvidenceGain=%d stall=%.0fs status=%s%n",
                                    elapsedMillis / 1000.0, authoritative.get("health").getAsFloat(), authoritative.get("food").getAsInt(),
                                    stats.worldTickAdvance(), stats.resourceUnitsGained, stats.craftedEvidenceUnitsGained,
                                    stats.stallMillis / 1000.0, sample.get("status").getAsString());
                            nextReport = System.currentTimeMillis() + 30_000;
                        }
                        if (stats.died) break;
                    }
                    stats.end = System.currentTimeMillis();
                    stats.durationCompleted = stats.end - stats.start >= minutes * 60_000L;
                    // Capture end state even when the last periodic snapshot was 29 seconds earlier.
                    JsonObject finalState = server.computeOnServer(mcServer -> {
                        JsonObject state = new JsonObject(); state.addProperty("worldTicks", mcServer.overworld().getGameTime());
                        state.addProperty("dayTime", mcServer.overworld().getOverworldClockTime());
                        state.add("inventory", counts(connection.getServerPlayer().getInventory())); return state;
                    });
                    stats.lastWorldTicks = finalState.get("worldTicks").getAsLong();
                    stats.lastDayTime = finalState.get("dayTime").getAsLong();
                    stats.inventory(finalState.getAsJsonObject("inventory"), stats.end - stats.start);
                    Files.writeString(reports.resolve("final-server-state.json"), finalState.toString(), StandardCharsets.UTF_8);
                } finally {
                    context.runOnClient(mc -> PartnerClient.instance().command("disable", ""));
                    connection.waitForServerboundPackets();
                }
            }
            if (stats.died) throw new AssertionError("Natural survival run ended in death; inspect evidence before iterating");
        } catch (Throwable error) {
            failure = error;
        } finally {
            if (stats.start > 0 && stats.end == 0) stats.end = System.currentTimeMillis();
            try { Files.createDirectories(reports); saveResult(reports, stats, true, failure); }
            catch (java.io.IOException writeError) {
                if (failure != null) failure.addSuppressed(writeError); else failure = writeError;
            }
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException error) throw error;
        if (failure != null) throw new RuntimeException(failure);
    }

    /** Every occupied slot, including main inventory, armor and offhand; never just the hotbar. */
    private static JsonObject counts(Inventory inventory) {
        TreeMap<String, Integer> counts = new TreeMap<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        JsonObject items = new JsonObject(); counts.forEach(items::addProperty); return items;
    }

    private static void saveResult(Path reports, RunStats stats, boolean finished, Throwable failure) throws java.io.IOException {
        Files.writeString(reports.resolve("result.json"), stats.result(finished, failure).toString(), StandardCharsets.UTF_8);
    }

    private static final class RunStats {
        private final int requestedMinutes;
        private long actualSeed = Long.MIN_VALUE, start, end;
        private long firstWorldTicks = -1, lastWorldTicks, firstDayTime = -1, lastDayTime;
        private long lastProgressMillis, stallMillis, maxStallMillis;
        private int samples, resourceUnitsGained, craftedEvidenceUnitsGained, inventoryChanges, minFood = 20;
        private float minHealth = Float.POSITIVE_INFINITY;
        private boolean died, durationCompleted;
        private double travelled;
        private double[] lastPoint;
        private Map<String, Integer> previousInventory;
        private final TreeMap<String, Integer> gains = new TreeMap<>();
        RunStats(int minutes) { requestedMinutes = minutes; }

        void add(JsonObject sample, long elapsedMillis) {
            JsonObject server = sample.getAsJsonObject("server");
            long ticks = server.get("worldTicks").getAsLong(), day = server.get("dayTime").getAsLong();
            if (firstWorldTicks < 0) { firstWorldTicks = ticks; firstDayTime = day; }
            lastWorldTicks = ticks; lastDayTime = day;
            Map<String, Integer> before = previousInventory == null ? Map.of() : new TreeMap<>(previousInventory);
            inventory(server.getAsJsonObject("inventory"), elapsedMillis);
            JsonObject delta = new JsonObject();
            TreeMap<String, Integer> keys = new TreeMap<>(previousInventory); before.forEach(keys::putIfAbsent);
            for (String id : keys.keySet()) {
                int change = previousInventory.getOrDefault(id, 0) - before.getOrDefault(id, 0);
                if (change != 0) delta.addProperty(id, change);
            }
            sample.add("inventoryDelta", delta);
            sample.addProperty("inventoryStallSeconds", stallMillis / 1000.0);
            sample.addProperty("resourceUnitsGained", resourceUnitsGained);
            sample.addProperty("craftedEvidenceUnitsGained", craftedEvidenceUnitsGained);
            double[] point = {sample.get("x").getAsDouble(), sample.get("y").getAsDouble(), sample.get("z").getAsDouble()};
            if (lastPoint != null) travelled += Math.sqrt(Math.pow(point[0]-lastPoint[0], 2) + Math.pow(point[1]-lastPoint[1], 2) + Math.pow(point[2]-lastPoint[2], 2));
            lastPoint = point; samples++;
        }

        void inventory(JsonObject inventory, long elapsedMillis) {
            TreeMap<String, Integer> current = new TreeMap<>(); inventory.entrySet().forEach(entry -> current.put(entry.getKey(), entry.getValue().getAsInt()));
            if (previousInventory == null) { previousInventory = current; lastProgressMillis = elapsedMillis; return; }
            boolean changed = !current.equals(previousInventory);
            for (var item : current.entrySet()) {
                int gained = item.getValue() - previousInventory.getOrDefault(item.getKey(), 0);
                if (gained <= 0) continue;
                gains.merge(item.getKey(), gained, Integer::sum);
                String name = item.getKey().substring(item.getKey().indexOf(':') + 1);
                if (name.endsWith("_log") || name.endsWith("_wood") || name.endsWith("_stem")
                        || java.util.Set.of("wheat", "cobblestone", "cobbled_deepslate", "coal", "raw_iron", "raw_copper", "raw_gold").contains(name)) resourceUnitsGained += gained;
                if (name.endsWith("_planks") || name.endsWith("_pickaxe") || name.endsWith("_axe") || name.endsWith("_sword")
                        || name.endsWith("_shovel") || name.endsWith("_hoe")
                        || java.util.Set.of("stick", "crafting_table", "furnace", "campfire", "torch").contains(name)) craftedEvidenceUnitsGained += gained;
            }
            if (changed) { inventoryChanges++; maxStallMillis = Math.max(maxStallMillis, elapsedMillis - lastProgressMillis); lastProgressMillis = elapsedMillis; }
            stallMillis = elapsedMillis - lastProgressMillis; maxStallMillis = Math.max(maxStallMillis, stallMillis);
            previousInventory = current;
        }

        long worldTickAdvance() { return firstWorldTicks < 0 ? 0 : Math.max(0, lastWorldTicks - firstWorldTicks); }
        JsonObject result(boolean finished, Throwable failure) {
            long actualMillis = start == 0 ? 0 : (end == 0 ? System.currentTimeMillis() : end) - start;
            long dayAdvance = firstDayTime < 0 ? 0 : Math.max(0, lastDayTime - firstDayTime);
            double ticksPerSecond = actualMillis == 0 ? 0 : worldTickAdvance() / (actualMillis / 1000.0);
            boolean fullCycle = dayAdvance >= 24000;
            boolean normalTicks = worldTickAdvance() >= requestedMinutes * 60L * 20L * .9;
            boolean basic = actualSeed == SEED && worldTickAdvance() >= 1200 && (resourceUnitsGained > 0 || craftedEvidenceUnitsGained > 0);
            boolean full = finished && failure == null && durationCompleted && !died && basic && fullCycle && normalTicks
                    && resourceUnitsGained > 0 && craftedEvidenceUnitsGained > 0;
            JsonObject data = new JsonObject();
            data.addProperty("requestedMinutes", requestedMinutes); data.addProperty("actualSeconds", actualMillis / 1000.0);
            data.addProperty("finished", finished); data.addProperty("durationCompleted", durationCompleted);
            data.addProperty("died", died); data.addProperty("minHealth", Float.isFinite(minHealth) ? minHealth : -1);
            data.addProperty("minFood", minFood); data.addProperty("sampledTravelDistance", travelled); data.addProperty("samples", samples);
            data.addProperty("requestedSeed", SEED); data.addProperty("actualSeed", actualSeed); data.addProperty("seedVerified", actualSeed == SEED);
            data.addProperty("worldTickAdvance", worldTickAdvance()); data.addProperty("dayTimeAdvance", dayAdvance);
            data.addProperty("effectiveTicksPerSecond", ticksPerSecond); data.addProperty("fullDayNightCycleObserved", fullCycle);
            data.addProperty("normalSimulationDurationPassed", normalTicks);
            data.addProperty("resourceUnitsGained", resourceUnitsGained); data.addProperty("craftedEvidenceUnitsGained", craftedEvidenceUnitsGained);
            data.addProperty("inventoryChanges", inventoryChanges); data.addProperty("inventoryStallSeconds", stallMillis / 1000.0);
            data.addProperty("longestInventoryStallSeconds", Math.max(maxStallMillis, actualMillis - lastProgressMillis) / 1000.0);
            JsonObject acquired = new JsonObject(); gains.forEach(acquired::addProperty); data.add("positiveInventoryDeltas", acquired);
            JsonObject finalInventory = new JsonObject(); if (previousInventory != null) previousInventory.forEach(finalInventory::addProperty);
            data.add("finalInventory", finalInventory);
            data.addProperty("basic_progress_passed", basic); data.addProperty("full_survival_passed", full);
            data.addProperty("basicProgressCriteria", "verified seed, >=1200 world ticks, resource or processed/crafted-evidence item gained");
            data.addProperty("fullSurvivalCriteria", "requested wall duration, no error/death, >=90% normal tick duration, >=24000 day ticks, resource and processed/crafted-evidence item gains");
            data.addProperty("craftedEvidenceLimitation", "inventory gains establish item possession, not individual recipe execution; no item gifts are used");
            data.addProperty("backend", "local Qwen3.5-4B, thinking off; model context verified by launcher separately");
            data.addProperty("windowVisible", !"true".equalsIgnoreCase(System.getenv("WILDLING_HEADLESS")));
            data.addProperty("testFramerateLimit", 30); data.addProperty("backgroundHeavyThrottlingDisabled", true);
            data.addProperty("outcome", !finished ? "running" : failure != null ? "failed_or_interrupted" : full ? "full_survival_passed" : basic ? "basic_progress_only" : "insufficient_progress");
            if (failure != null) data.addProperty("failure", failure.getClass().getName() + ": " + failure.getMessage());
            return data;
        }
    }
}
