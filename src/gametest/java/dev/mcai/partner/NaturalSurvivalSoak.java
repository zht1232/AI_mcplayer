package dev.mcai.partner;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Opt-in real-time generated-world run. No mocked model, inventory gifts, healing or time acceleration. */
final class NaturalSurvivalSoak {
    static int minutes() { String value = System.getenv("WILDLING_SOAK_MINUTES"); return value == null ? 0 : Math.clamp(Integer.parseInt(value), 0, 120); }
    static void run(ClientGameTestContext context) {
        int minutes = minutes();
        Path reports = Path.of(System.getenv().getOrDefault("WILDLING_SOAK_REPORT_DIR", "soak-reports")).toAbsolutePath();
        Properties properties = new Properties();
        properties.setProperty("online-mode", "false"); properties.setProperty("spawn-protection", "0");
        properties.setProperty("enforce-secure-profile", "false"); properties.setProperty("difficulty", "normal");
        properties.setProperty("level-seed", "20261005"); properties.setProperty("view-distance", "6");
        long start = System.currentTimeMillis(), nextReport = start; int samples = 0;
        boolean died = false; double travelled = 0; double[] last = null;
        try {
            Files.createDirectories(reports);
            Path timeline = reports.resolve("timeline.jsonl"); Files.writeString(timeline, "", StandardCharsets.UTF_8);
            try (TestDedicatedServerContext server = context.worldBuilder().setUseConsistentSettings(false).createServer(properties);
                 TestDedicatedServerConnection connection = server.connect()) {
                connection.waitForChunksDownload();
                context.runOnClient(mc -> {
                    var partner = PartnerClient.instance(); partner.config().baseUrl = "http://127.0.0.1:8080/v1";
                    partner.config().model = "Qwen3.5-4B"; partner.config().apiKey = ""; partner.config().apiKeyEnv = "";
                    partner.config().enableThinking = false; partner.config().reasoningProtocol = "llama";
                    if (partner.command("enable", "") != 1) throw new AssertionError("natural-world activation failed");
                });
                start = System.currentTimeMillis(); nextReport = start;
                while (System.currentTimeMillis() - start < minutes * 60_000L) {
                    context.waitTicks(20);
                    boolean alive = context.computeOnClient(mc -> mc.player != null && mc.player.isAlive());
                    if (!alive) { died = true; break; }
                    if (System.currentTimeMillis() >= nextReport) {
                        long elapsed = System.currentTimeMillis() - start;
                        JsonObject sample = context.computeOnClient(mc -> {
                            var partner = PartnerClient.instance(); JsonObject data = new JsonObject();
                            data.addProperty("elapsedSeconds", elapsed / 1000); data.addProperty("x", mc.player.getX()); data.addProperty("y", mc.player.getY()); data.addProperty("z", mc.player.getZ());
                            data.addProperty("health", mc.player.getHealth()); data.addProperty("food", mc.player.getFoodData().getFoodLevel());
                            data.addProperty("status", partner.status()); data.addProperty("modelMetrics", partner.plannerSummary()); data.addProperty("lastError", partner.lastError());
                            JsonObject items = new JsonObject();
                            for (String item : java.util.List.of("oak_log", "birch_log", "spruce_log", "wheat", "bread", "apple", "cooked_beef", "beef", "crafting_table", "wooden_pickaxe", "stone_pickaxe")) items.addProperty(item, partner.motor().inventoryCount("minecraft:" + item));
                            data.add("inventory", items); return data;
                        });
                        double[] point = {sample.get("x").getAsDouble(), sample.get("y").getAsDouble(), sample.get("z").getAsDouble()};
                        if (last != null) travelled += Math.sqrt(Math.pow(point[0]-last[0],2)+Math.pow(point[1]-last[1],2)+Math.pow(point[2]-last[2],2));
                        last = point; samples++;
                        Files.writeString(timeline, sample + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                        System.out.println("WILDLING_SOAK " + sample);
                        nextReport = System.currentTimeMillis() + 30_000;
                    }
                }
                context.runOnClient(mc -> PartnerClient.instance().command("disable", ""));
                connection.waitForServerboundPackets();
            }
            JsonObject result = new JsonObject(); result.addProperty("requestedMinutes", minutes); result.addProperty("actualSeconds", (System.currentTimeMillis()-start)/1000);
            result.addProperty("died", died); result.addProperty("sampledTravelDistance", travelled); result.addProperty("samples", samples); result.addProperty("seed", "20261005");
            result.addProperty("backend", "local Qwen3.5-4B, thinking off");
            Files.writeString(reports.resolve("result.json"), result.toString(), StandardCharsets.UTF_8);
            if (died) throw new AssertionError("Natural survival run ended in death; inspect timeline before iterating");
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
    }
}
