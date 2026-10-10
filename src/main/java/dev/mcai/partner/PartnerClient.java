package dev.mcai.partner;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.mcai.partner.brain.AiBrain;
import dev.mcai.partner.ui.UiBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class PartnerClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("mc-ai-partner");
    private static PartnerClient instance;
    private final Minecraft mc = Minecraft.getInstance();
    private final ControlState control = new ControlState();
    private final ArrayDeque<String> feedback = new ArrayDeque<>();
    private final ArrayDeque<AiBrain.Action> actions = new ArrayDeque<>();
    private record Speech(String text, long tick) {}
    private final ArrayDeque<Speech> recentSpeech = new ArrayDeque<>();
    private final SpeechPolicy speechPolicy = new SpeechPolicy();
    private final InventoryOpenPolicy inventoryPolicy = new InventoryOpenPolicy();
    private HarvestSkill harvest;
    private BasicCraftSkill basicCraft;
    private WorkbenchSkill workbench;
    private ForageSkill forage;
    private long nextForage;
    private long nextBasicCraft;
    private long workProgress;
    private int narrationOnlyDecisions;
    private long lastDecisionProgress = -1;
    private long nextFallback;
    private final ArrayDeque<BlockPos> recentExploration = new ArrayDeque<>();
    private RuntimeSkillBook skillBook;
    private long collectionGeneration = -1;
    private long nextHelpRequest;
    private int helpUrgency;
    private boolean emittingSpeech;
    private final ArrayDeque<JsonObject> dialogue = new ArrayDeque<>();
    private PartnerConfig config;
    private UiBridge ui;
    private ClientMotor motor;
    private AiBrain brain;
    private CompletableFuture<AiBrain.Decision> pending;
    private KeyMapping panelKey;
    private long ticks;
    private long nextDecision;
    private long requestStartedNanos;
    private long actionGeneration;
    private long memoryRevision;
    private String lastSpeech = "";
    private String lastError = "";
    private String targetItem;
    private int targetCount;
    private int targetStartCount;
    private int targetStableTicks;
    private net.minecraft.world.phys.Vec3 manualMoveTarget;
    private int movementStableTicks;
    private boolean configurationReady = true;
    private boolean modelActionActive;
    private final java.util.Set<String> requestedObservation = new java.util.HashSet<>();
    private Integer inspectedSlot;
    private int plannerCharacters;
    private long lastRequestMilliseconds;
    private boolean finishConversationAfterReply;

    public static PartnerClient instance() { return instance; }
    public void serverBlockUpdate(BlockPos position, net.minecraft.world.level.block.state.BlockState state) { if (motor != null) motor.serverBlockUpdate(position, state); }
    @Override public void onInitializeClient() {
        instance = this;
        try { config = PartnerConfig.load(); } catch (Exception e) { LOG.error("Invalid config; AI will require correction", e); configurationReady = false; config = new PartnerConfig(); lastError = "配置读取失败：" + e.getMessage(); }
        ui = new UiBridge(mc); motor = new ClientMotor(mc, config.navigationRange); harvest = new HarvestSkill(mc, motor);
        basicCraft = new BasicCraftSkill(mc);
        workbench = new WorkbenchSkill(mc, motor); forage = new ForageSkill(mc, motor);
        try { skillBook = new RuntimeSkillBook(FabricLoader.getInstance().getConfigDir().resolve("wildling/skills")); }
        catch (java.io.IOException e) { LOG.warn("Could not initialize skill knowledge: {}", e.getClass().getSimpleName()); }
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("mc_ai_partner", "controls"));
        panelKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mc_ai_partner.panel", GLFW.GLFW_KEY_F8, category));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            control.connect(); ui.clear(); feedback.clear(); actions.clear(); recentSpeech.clear(); recentExploration.clear(); dialogue.clear(); speechPolicy.clear(); workProgress = 0;
            notice("已连接。F8 打开面板；/aip enable 开启当前账号的 AI。");
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { control.disconnect(); invalidate(); motor.active(false); ui.clear(); });
        ClientSendMessageEvents.ALLOW_CHAT.register(text -> {
            if (!control.enabled() || emittingSpeech || modelActionActive || mc.player == null) return true;
            ChatAddressing.signed(text, "本地用户", mc.player.getGameProfile().name(), config.publicCommandPrefix).ifPresent(request -> {
                if (request.stop()) stop(); else takeLocalControl(request.text());
            });
            return true;
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, received) -> {
            ui.onMessage(message, false);
            if (mc.player == null) return;
            if (sender != null && sender.id().equals(mc.player.getUUID())) return;
            ChatAddressing.signed(signed == null ? message.getString() : signed.signedContent(),
                    sender == null ? "玩家" : sender.name(), mc.player.getGameProfile().name(), config.publicCommandPrefix)
                    .ifPresent(this::publicInstruction);
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            ui.onMessage(message, overlay);
            if (!overlay && mc.player != null && mc.getConnection() != null) {
                String line = message.getString();
                String normalized = line.replaceAll("[\\p{Z}\\s§]", "");
                if (recentSpeech.stream().anyMatch(spoken -> ticks - spoken.tick() <= 1200
                        && normalized.endsWith(spoken.text().replaceAll("[\\p{Z}\\s§]", "")))) return;
                ChatAddressing.decorated(line, mc.player.getGameProfile().name(), config.publicCommandPrefix,
                        mc.getConnection().getOnlinePlayers().stream().map(player -> player.getProfile().name()).toList())
                        .ifPresent(this::publicInstruction);
            }
        });
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (control.enabled() && !(mc.gui.screen() instanceof PartnerPanel)) motor.tick();
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            dispatcher.register(literal("aip")
                .executes(ctx -> command("status", ""))
                .then(literal("enable").executes(ctx -> command("enable", "")))
                .then(literal("disable").executes(ctx -> command("disable", "")))
                .then(literal("stop").executes(ctx -> command("stop", "")))
                .then(literal("release").executes(ctx -> command("release", "")))
                .then(literal("auto").then(argument("value", StringArgumentType.word()).executes(ctx -> command("auto", StringArgumentType.getString(ctx, "value")))))
                .then(literal("goal").then(argument("text", StringArgumentType.greedyString()).executes(ctx -> command("goal", StringArgumentType.getString(ctx, "text")))))
                .then(literal("collect").then(argument("item", StringArgumentType.word()).then(argument("count", IntegerArgumentType.integer(1, 256)).executes(ctx -> {
                    String item = StringArgumentType.getString(ctx, "item");
                    if (!net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(Identifier.parse(item))) { notice("未知原版物品 ID"); return 0; }
                    int count = IntegerArgumentType.getInteger(ctx, "count");
                    takeLocalControl("采集新增 " + count + " 个 " + item + "；不足工具时先获得必要工具，拾取后检查背包。");
                    targetItem = item; targetStartCount = motor.inventoryCount(item); targetCount = targetStartCount + count; targetStableTicks = 0;
                    return 1;
                }))))
                .then(literal("status").executes(ctx -> command("status", "")))
                .then(literal("inspect").executes(ctx -> command("inspect", "")))
                .then(literal("reload").executes(ctx -> command("reload", "")))
                .then(literal("thinking").then(argument("value", StringArgumentType.word()).executes(ctx -> command("thinking", StringArgumentType.getString(ctx, "value")))))
                .then(literal("reasoningprotocol").then(argument("value", StringArgumentType.word()).executes(ctx -> command("reasoningprotocol", StringArgumentType.getString(ctx, "value")))))
                .then(literal("model").then(argument("value", StringArgumentType.greedyString()).executes(ctx -> command("model", StringArgumentType.getString(ctx, "value")))))
                .then(literal("endpoint").then(argument("value", StringArgumentType.greedyString()).executes(ctx -> command("endpoint", StringArgumentType.getString(ctx, "value")))))
                .then(literal("keyenv").then(argument("value", StringArgumentType.word()).executes(ctx -> command("keyenv", StringArgumentType.getString(ctx, "value")))))
                .then(literal("click").then(argument("slot", IntegerArgumentType.integer(0)).then(argument("button", StringArgumentType.word()).executes(ctx -> {
                    requireEnabled(); motor.stop(); notice(ui.clickSlot(IntegerArgumentType.getInteger(ctx, "slot"), StringArgumentType.getString(ctx, "button"), ui.snapshot().get("revision").getAsLong())); return 1;
                }))))
                .then(literal("chatclick").then(argument("id", IntegerArgumentType.integer(1)).executes(ctx -> { requireEnabled(); notice(ui.clickChat(IntegerArgumentType.getInteger(ctx, "id"))); return 1; })))
                .then(literal("go").then(argument("x", IntegerArgumentType.integer()).then(argument("y", IntegerArgumentType.integer()).then(argument("z", IntegerArgumentType.integer()).executes(ctx -> {
                    notice(moveManually(new BlockPos(IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z")))); return 1;
                }))))));
            dispatcher.register(literal("wildling").redirect(dispatcher.getRoot().getChild("aip")));
        });
        LOG.info("Wildling initialized for Minecraft 26.2; activation requires an entered multiplayer session");
    }
    private void requireConnected() {
        if (modelActionActive) throw new IllegalStateException("本地控制命令须由本地用户直接操作");
        if (!control.connected() || mc.player == null || mc.level == null || mc.isLocalServer()) throw new IllegalStateException("请先进入多人服务器");
    }
    private void requireEnabled() { requireConnected(); if (!control.enabled()) throw new IllegalStateException("请先用 /aip enable 开启 AI"); }
    public PartnerConfig config() { return config; }
    public UiBridge uiBridge() { return ui; }
    public ClientMotor motor() { return motor; }
    public boolean enabled() { return control.enabled(); }
    public boolean publicTaskActive() { return control.publicGoal(); }
    public boolean collectionActive() { return targetItem != null; }
    public boolean executionActive() { return motor.busy() || harvest.busy() || basicCraft.busy() || workbench.busy() || forage.busy(); }
    public String status() {
        String activity = motor.survivalBusy() ? motor.status() : workbench.busy() ? "工作台合成"
                : basicCraft.busy() ? "基础合成" : forage.busy() ? "觅食" : harvest.busy() ? "采集"
                : pending == null ? motor.status() : "等待模型 " + (System.nanoTime() - requestStartedNanos) / 1_000_000_000L + " 秒";
        activity = activity.replace("idle", "待命").replace("walking", "行走").replace("digging", "采掘").replace("eating", "进食").replace("following", "跟随");
        return (control.enabled() ? "运行" : "暂停") + " / " + activity + " / " + (control.localControl() ? "本地优先" : "自主/对话") + " / 排队 " + control.queued();
    }
    public String lastSpeech() { return lastSpeech; }
    public String lastError() { return lastError; }
    public String plannerSummary() { return "观察 " + plannerCharacters + " 字符 · 最近请求 " + String.format(java.util.Locale.ROOT, "%.1f", lastRequestMilliseconds / 1000.0) + " 秒"; }
    public void openPanel() {
        requireConnected(); invalidateRequest(); motor.stop();
        if (manualMoveTarget != null) { manualMoveTarget = null; control.complete(); }
        mc.gui.setScreen(new PartnerPanel(this));
    }
    public void enable() {
        requireConnected();
        if (!configurationReady) throw new IllegalStateException("先修复 " + PartnerConfig.path() + " 并用 /aip reload 重载");
        invalidate(); config.validate();
        brain = new AiBrain(new AiBrain.Options(config.baseUrl, config.model, config.apiKeyEnv, config.maxTokens, config.timeoutSeconds,
                config.enableThinking, config.reasoningProtocol, config.apiKey), ToolCatalog.schemas());
        control.enable(); control.autonomous(true); motor.active(true); nextDecision = ticks; lastError = "";
        notice("拾野开始接管。提到或 @" + mc.player.getGameProfile().name() + " 可交流；本地控制优先。");
    }
    public void disable() { control.disable(); targetItem = null; manualMoveTarget = null; invalidate(); motor.active(false); notice("拾野已暂停。"); }
    /** The local panel saves its whole connection at once; secrets never travel through chat commands. */
    public void saveConnection(String address, String model, String apiKey, boolean thinking, String protocol) throws java.io.IOException {
        PartnerConfig updated = config.copy();
        updated.baseUrl = address; updated.model = model; updated.apiKey = apiKey;
        // Direct entry replaces the legacy environment-variable source, including when cleared.
        updated.apiKeyEnv = "";
        updated.enableThinking = thinking; updated.reasoningProtocol = protocol;
        updated.validate();
        new AiBrain.Options(updated.baseUrl, updated.model, updated.apiKeyEnv, updated.maxTokens,
                updated.timeoutSeconds, updated.enableThinking, updated.reasoningProtocol, updated.apiKey);
        disable(); updated.save(); config = updated; configurationReady = true;
        notice("连接设置已保存，开始接管后生效。");
    }
    public void stop() { control.stop(); targetItem = null; manualMoveTarget = null; invalidateRequest(); motor.stop(); notice("已停止任务和自主规划。/aip auto on 恢复自主。"); }
    public void takeLocalControl(String goal) {
        requireEnabled(); targetItem = null; manualMoveTarget = null; control.submit(goal, ControlState.Priority.LOCAL); invalidateRequest(); motor.stop(); nextDecision = ticks;
        rememberResult("New local instruction: " + goal);
        dialogue("user", "本地用户", goal);
    }
    public String moveManually(BlockPos position) {
        takeLocalControl("移动到 " + position);
        String result = motor.moveTo(position); manualMoveTarget = motor.destination(); movementStableTicks = 0;
        if (result.startsWith("FAILED")) control.complete();
        return result;
    }
    private void publicInstruction(ChatAddressing.Request message) {
        if (!control.enabled()) return;
        if (message.stop()) {
            if (control.publicStop()) { invalidateRequest(); motor.stop(); notice("收到公共停止指令。"); say("好，我停下了。"); }
            else if (control.localControl()) say("我正在处理本地安排，暂时不能停下。请在本地面板控制。");
            return;
        }
        String request = "玩家 " + message.speaker() + " 对你说：" + message.text()
                + "\n请像正常玩家一样用简短自然中文回复。若只是聊天，回复后调用 complete_goal 结束；若要求干活，回应后执行并核对结果。";
        long old = control.generation();
        if (control.submit(request, ControlState.Priority.PUBLIC)) {
            dialogue("user", message.speaker(), message.text());
            if (old != control.generation()) { invalidateRequest(); motor.stop(); nextDecision = ticks; }
            else if (control.localControl()) say("收到，我先完成当前的本地安排，再处理你的消息。");
            rememberResult("Public player instruction accepted from " + message.speaker() + ": " + message.text());
        }
    }
    private void invalidateRequest() {
        // cancel() can invoke completion callbacks inline on this client thread.
        // Invalidate their captured revision before cancelling the old future.
        memoryRevision++;
        if (harvest != null) harvest.stop();
        if (basicCraft != null) basicCraft.cancel();
        if (workbench != null) workbench.cancel();
        if (forage != null) forage.cancel();
        narrationOnlyDecisions = 0;
        lastDecisionProgress = -1;
        CompletableFuture<AiBrain.Decision> obsolete = pending;
        pending = null;
        actions.clear();
        requestedObservation.clear(); inspectedSlot = null;
        finishConversationAfterReply = false;
        nextDecision = ticks;
        if (obsolete != null) obsolete.cancel(true);
    }
    private void invalidate() { invalidateRequest(); if (brain != null) brain.close(); brain = null; }
    private void cancelPlannerKeepExecution() {
        if (pending == null) return;
        memoryRevision++; var obsolete = pending; pending = null; obsolete.cancel(true);
        nextDecision = ticks + config.decisionIntervalSeconds * 20L;
        rememberResult("Planner interrupted: deterministic autonomous work already started; re-observe its real result before another decision.");
    }
    private String serverKey() { return mc.getCurrentServer() == null ? "" : mc.getCurrentServer().ip; }
    private void dialogue(String role, String speaker, String text) {
        JsonObject turn = new JsonObject(); turn.addProperty("role", role); turn.addProperty("speaker", speaker); turn.addProperty("text", text.substring(0, Math.min(240, text.length())));
        dialogue.addLast(turn); while (dialogue.size() > 6) dialogue.removeFirst();
    }
    private void rememberResult(String result) {
        feedback.addLast(result); while (feedback.size() > 12) feedback.removeFirst(); LOG.info("Action feedback: {}", result);
        if (skillBook != null) try { skillBook.feedback(serverKey(), result); }
        catch (java.io.IOException e) { LOG.warn("Could not save bounded experience: {}", e.getClass().getSimpleName()); }
    }
    public void notice(String text) { lastSpeech = text; LOG.info("{}", text); }
    /** Natural speech uses normal signed player chat; local status and errors stay in notice(). */
    private String say(String value) { return say(value, false); }
    private String say(String value, boolean work) {
        if (value == null || value.isBlank()) return "FAILED: speech is empty";
        String text = value.replaceAll("[\\r\\n\\t]+", " ").strip();
        if (text.startsWith("/") || text.startsWith(config.publicCommandPrefix.strip())) return "FAILED: speech must not be a command";
        if (text.length() > 256) text = text.substring(0, 256);
        if (targetItem != null && motor.inventoryCount(targetItem) < targetCount && text.matches("(?is).*(已经.*完成|收割完成|收割完了|收集完成|已完成).*"))
            return "SKIPPED: completion claim does not match the verified inventory quota";
        if (!speechPolicy.allows(text, ticks, work, control.generation(), workProgress))
            return "SKIPPED: public update needs new progress or is too similar/recent";
        String result; emittingSpeech = true;
        try { result = ui.inputChat(text); } finally { emittingSpeech = false; }
        if (result.startsWith("已发送")) {
            recentSpeech.addLast(new Speech(text, ticks));
            speechPolicy.sent(text, ticks, work, control.generation(), workProgress);
            while (recentSpeech.size() > 8) recentSpeech.removeFirst();
            lastSpeech = text;
            dialogue("assistant", mc.player.getGameProfile().name(), text);
            LOG.info("Player speech: {}", text);
        }
        return result;
    }
    private JsonObject observation() {
        JsonObject observation = new JsonObject(); observation.add("selfAndWorld", motor.snapshot(config.scanRadius)); observation.add("ui", ui.snapshot());
        observation.addProperty("server", mc.getCurrentServer() == null ? "" : mc.getCurrentServer().ip);
        observation.addProperty("playerName", mc.player == null ? "" : mc.player.getGameProfile().name());
        observation.addProperty("localControl", control.localControl()); observation.addProperty("notes", config.notes);
        if (targetItem != null) { observation.addProperty("requiredItem", targetItem); observation.addProperty("requiredTotal", targetCount); observation.addProperty("currentCount", motor.inventoryCount(targetItem)); }
        return observation;
    }
    private JsonObject modelObservation(String goal) {
        JsonObject view = ModelObservation.select(observation(), goal, requestedObservation, inspectedSlot);
        if (!ModelObservation.isWorkGoal(goal)) { JsonArray history = new JsonArray(); dialogue.forEach(history::add); view.add("conversationHistory", history); }
        if (skillBook != null) try { view.add("skillKnowledge", skillBook.relevant(serverKey(), goal + " " + String.join(" ", feedback), view.get("observationMode").getAsString())); }
        catch (java.io.IOException e) { LOG.warn("Could not read bounded experience: {}", e.getClass().getSimpleName()); }
        requestedObservation.clear(); inspectedSlot = null;
        plannerCharacters = view.toString().length();
        LOG.info("Planner view: mode={}, characters={}", view.get("observationMode"), plannerCharacters);
        return view;
    }
    private void tick() {
        ticks++;
        while (panelKey.consumeClick()) try { openPanel(); } catch (Exception e) { notice(e.getMessage()); }
        if (mc.player == null || mc.level == null) {
            if (control.enabled()) { control.disconnect(); invalidate(); motor.active(false); }
            return;
        }
        ui.tick();
        if (!control.enabled() || mc.gui.screen() instanceof PartnerPanel || mc.isPaused()) return;
        if (ScreenPolicy.blocksWorld(mc.gui.screen()) && motor.busy()) {
            motor.stop(); actions.clear(); rememberResult("World task interrupted by UI; inspect and operate the current screen before continuing.");
        }
        if (!mc.player.isAlive()) {
            if (motor.busy() || pending != null || !actions.isEmpty() || manualMoveTarget != null || targetItem != null) {
                invalidateRequest(); motor.stop();
                if (manualMoveTarget != null || targetItem != null) control.complete();
                manualMoveTarget = null; targetItem = null;
                rememberResult("Player died; old actions and typed goals cancelled. Re-observe after respawn.");
            }
            return;
        }
        for (String result : motor.drainResults()) {
            if (result.startsWith("SURVIVAL INTERRUPT")) { invalidateRequest(); manualMoveTarget = null; }
            int urgency = mc.player.getHealth() <= 6 ? 3 : 2;
            if (result.startsWith("NEED_HELP") && (ticks >= nextHelpRequest || urgency > helpUrgency)) {
                nextHelpRequest = ticks + 1200;
                helpUrgency = urgency;
                String peer = mc.getConnection() == null ? "" : mc.getConnection().getOnlinePlayers().stream()
                        .map(p -> p.getProfile().name()).filter(name -> !name.equalsIgnoreCase(mc.player.getGameProfile().name())).findFirst().orElse("");
                say((peer.isEmpty() ? "" : "@" + peer + " ") + (result.contains("food") ? "我缺吃的，血量或饥饿有危险。能给我一点食物吗？" : "我被困住了，找不到安全出口。能帮我打开一条路吗？"));
            }
            if (result.startsWith("DEFENCE STARTED")) {
                invalidateRequest();
                if (manualMoveTarget != null) { manualMoveTarget = null; control.complete(); }
                notice("遭遇近身威胁，暂停工作并立即防御。");
            }
            if (result.startsWith("DEFENCE ENDED")) nextDecision = ticks;
            harvest.feedback(result);
            if (result.startsWith("OK: server confirmed") || result.startsWith("OK: arrived") || result.startsWith("Eating ended")) workProgress++;
            rememberResult(result);
            if (manualMoveTarget != null && result.startsWith("FAILED")) { manualMoveTarget = null; control.complete(); notice(result); }
        }
        if (motor.defending() && mc.player.getHealth() <= 6 && !motor.hasFood() && (ticks >= nextHelpRequest || helpUrgency < 3)) {
            nextHelpRequest = ticks + 1200; helpUrgency = 3;
            say("我血量很低，还缺食物，附近有人能帮我脱离战斗、给点补给吗？");
        }
        basicCraft.tick();
        String crafted = basicCraft.drainResult();
        if (crafted != null) { rememberResult("craft_basic: " + crafted); if (crafted.startsWith("OK")) { workProgress++; nextBasicCraft = ticks; } }
        if (basicCraft.busy()) return;
        if (workbench.busy() && mc.player.getHealth() <= 12) invalidateRequest();
        workbench.tick();
        String tooled = workbench.drainResult();
        if (tooled != null) { rememberResult("craft_workbench: " + tooled); if (tooled.startsWith("OK")) { workProgress++; nextBasicCraft = ticks; } }
        if (workbench.busy()) return;
        forage.tick();
        String foraged = forage.drainResult();
        if (foraged != null) { rememberResult("forage_food: " + foraged); if (foraged.startsWith("OK")) workProgress++; }
        if (forage.busy()) return;
        if (control.autonomous() && !control.localControl() && !control.publicGoal() && pending != null
                && System.nanoTime() - requestStartedNanos > 5_000_000_000L && !motor.busy() && !harvest.busy()
                && ticks >= nextFallback && !ScreenPolicy.blocksWorld(mc.gui.screen())) {
            JsonObject current = new JsonObject(); current.add("selfAndWorld", motor.snapshot(config.scanRadius));
            if (startFallback(current)) { cancelPlannerKeepExecution(); return; }
        }
        if (targetItem != null && motor.inventoryCount(targetItem) >= targetCount) {
            if (targetStableTicks == 0) { invalidateRequest(); if (!motor.survivalBusy()) motor.stop(); }
            if (++targetStableTicks >= 20) {
                notice("采集数量已通过背包检查：" + targetItem + " × " + motor.inventoryCount(targetItem));
                rememberResult("VERIFIED inventory quota: " + targetItem + " gained " + (motor.inventoryCount(targetItem) - targetStartCount));
                targetItem = null; control.complete(); invalidateRequest(); nextDecision = ticks + 20;
            }
            return; // Stop gathering immediately, then verify across consecutive ticks.
        }
        targetStableTicks = 0;
        harvest.tick();
        String harvested = harvest.drainOutcome();
        if (harvested != null) { rememberResult("harvest_block: " + harvested); nextDecision = ticks + 20; }
        if (harvest.busy()) return;
        if (manualMoveTarget != null) {
            movementStableTicks = mc.player.position().distanceTo(manualMoveTarget) < .7 ? movementStableTicks + 1 : 0;
            if (movementStableTicks >= 20) { manualMoveTarget = null; control.complete(); motor.stop(); notice("本地移动已到达并稳定停下。"); }
            return; // A deterministic local movement does not need an LLM request.
        }
        configureQuantityTask();
        if (control.autonomous() && !control.localControl() && !control.publicGoal() && targetItem == null && !motor.busy()
                && !harvest.busy() && pending == null && ticks >= nextForage && !motor.hasFood() && !ScreenPolicy.blocksWorld(mc.gui.screen())) {
            nextForage = ticks + 200; String food = forage.start();
            if (food.startsWith("STARTED")) { actions.clear(); rememberResult("Autonomous forage_food: " + food); return; }
        }
        if (control.autonomous() && !control.localControl() && !control.publicGoal() && targetItem == null && !motor.busy() && !harvest.busy()
                && pending == null && ticks >= nextBasicCraft && mc.player.getHealth() > 12 && !ScreenPolicy.blocksWorld(mc.gui.screen())) {
            int logs = 0, planks = 0;
            for (int slot = 0; slot < 36; slot++) {
                var stack = mc.player.getInventory().getItem(slot); String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (id.endsWith("_log")) logs += stack.getCount(); if (id.endsWith("_planks")) planks += stack.getCount();
            }
            String recipe = planks >= 4 && motor.inventoryCount("minecraft:crafting_table") == 0 && !workbench.hasReachableTable() ? "crafting_table"
                    : planks >= 2 && motor.inventoryCount("minecraft:stick") < 4 ? "sticks" : logs > 0 && planks < 8 ? "planks" : "";
            if (!recipe.isEmpty()) {
                nextBasicCraft = ticks + 200; String craft = basicCraft.start(recipe);
                rememberResult("Autonomous craft_basic: " + craft); if (craft.startsWith("STARTED")) { actions.clear(); return; }
            } else if (planks >= 3 && motor.inventoryCount("minecraft:stick") >= 2) {
                String tool = motor.inventoryCount("minecraft:wooden_axe") == 0 ? "wooden_axe"
                        : motor.inventoryCount("minecraft:wooden_pickaxe") == 0 ? "wooden_pickaxe" : "";
                if (!tool.isEmpty()) { nextBasicCraft = ticks + 200; String craft = workbench.start(tool); rememberResult("Autonomous craft_workbench: " + craft); if (craft.startsWith("STARTED")) { actions.clear(); return; } }
            }
        }
        if (targetItem != null && !motor.busy() && pending == null && !ScreenPolicy.blocksWorld(mc.gui.screen())) {
            JsonObject current = new JsonObject(); current.add("selfAndWorld", motor.snapshot(config.scanRadius));
            if (startFallback(current)) return;
        }
        if (control.autonomous() && !control.localControl() && !control.publicGoal() && pending == null
                && !motor.busy() && !harvest.busy() && ticks >= nextFallback && !ScreenPolicy.blocksWorld(mc.gui.screen())) {
            JsonObject current = new JsonObject(); current.add("selfAndWorld", motor.snapshot(config.scanRadius));
            if (startFallback(current)) return; // Basic work does not wait for a narration/LLM failure first.
        }
        if (!motor.busy() && !actions.isEmpty()) {
            if (actionGeneration != control.generation()) { actions.clear(); return; }
            AiBrain.Action action = actions.removeFirst();
            try {
                String result = perform(action);
                rememberResult(action.tool() + ": " + result);
                if (finishConversationAfterReply && (control.publicGoal() || control.localControl()) && action.tool().equals("chat_say")
                        && (result.startsWith("已发送") || result.startsWith("SKIPPED: public update"))) {
                    control.complete(); actions.clear(); finishConversationAfterReply = false;
                    rememberResult("Conversation answered through chat_say; proceeding to the next instruction.");
                }
            }
            catch (Exception e) { rememberResult(action.tool() + " FAILED: " + e.getMessage()); }
            return;
        }
        if (motor.busy() || pending != null || ticks < nextDecision || brain == null) return;
        String goal = control.goal(config.survivalGoal);
        if (goal.isBlank()) return;
        long generation = control.generation(); long revision = memoryRevision;
        JsonObject observation = modelObservation(goal);
        requestStartedNanos = System.nanoTime();
        pending = brain.request(observation, config.personality
                + "\n你的回复会作为当前玩家发送到服务器聊天。用自然中文回应玩家；自主工作时只在值得交流的进展、失败或需要帮助时说话，避免每轮播报计划。"
                + "\n当前目标：" + goal, List.copyOf(feedback));
        pending.whenComplete((decision, error) -> mc.execute(() -> {
            if (!control.enabled() || generation != control.generation() || revision != memoryRevision) return;
            pending = null; nextDecision = ticks + config.decisionIntervalSeconds * 20L;
            lastRequestMilliseconds = (System.nanoTime() - requestStartedNanos) / 1_000_000L;
            LOG.info("Planner response: milliseconds={}, viewCharacters={}", lastRequestMilliseconds, plannerCharacters);
            if (error != null) {
                Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause();
                String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                LOG.warn("Planner request failed: {}", message);
                if (cause instanceof java.nio.channels.ClosedChannelException || cause instanceof java.net.ConnectException || cause instanceof java.net.SocketException)
                    message = "模型服务连接已断开，请启动模型或检查接口地址。";
                else if (cause instanceof java.net.http.HttpTimeoutException) message = "模型响应超时，请检查服务和输入大小。";
                if (!message.equals(lastError)) notice("模型请求失败：" + message);
                lastError = message; nextDecision = ticks + Math.max(30, config.decisionIntervalSeconds) * 20L;
                return;
            }
            lastError = "";
            finishConversationAfterReply = (control.publicGoal() || control.localControl()) && !ModelObservation.isWorkGoal(goal)
                    && decision.actions().stream().anyMatch(action -> action.tool().equals("chat_say"))
                    && decision.actions().stream().allMatch(action -> action.tool().equals("chat_say") || action.tool().equals("complete_goal"));
            boolean conversation = !ModelObservation.isWorkGoal(goal);
            boolean actionable = decision.actions().stream().anyMatch(action -> !List.of("chat_say", "remember", "wait", "complete_goal", "observe", "inspect_slot").contains(action.tool()));
            if (!decision.speech().isBlank()) {
                if (conversation && !finishConversationAfterReply) say(decision.speech());
                else notice(decision.speech().replaceAll("[\\r\\n]+", " "));
            }
            if ((control.publicGoal() || control.localControl()) && conversation
                    && !decision.speech().isBlank() && decision.actions().isEmpty()) {
                control.complete();
                rememberResult("Conversation answered; proceeding to the next queued instruction.");
            }
            if (!conversation) {
                narrationOnlyDecisions = lastDecisionProgress == workProgress ? narrationOnlyDecisions + 1 : 0;
                lastDecisionProgress = workProgress;
                if (!actionable) rememberResult("NO_WORLD_ACTION: plan narration is not execution. Use harvest_block for an observed resource or another native action.");
                if (narrationOnlyDecisions >= 2 && startFallback(observation)) return;
            } else narrationOnlyDecisions = 0;
            actions.addAll(decision.actions()); actionGeneration = generation;
        }));
    }
    private BlockPos position(JsonObject args) { return new BlockPos(args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt()); }
    /** Small recovery for autonomous/typed collection only; never substitutes an unrelated player's goal. */
    private void configureQuantityTask() {
        if (collectionGeneration == control.generation()) return;
        collectionGeneration = control.generation();
        if (targetItem != null || (!control.localControl() && !control.publicGoal())) return;
        String text = control.goal(""); int addressed = text.indexOf("对你说：");
        if (addressed >= 0) text = text.substring(addressed + 4).split("\n", 2)[0];
        if (!text.matches("(?is).*(收|割|采|砍).*")) return;
        if (text.contains("小麦")) targetItem = "minecraft:wheat";
        else if (text.contains("胡萝卜")) targetItem = "minecraft:carrot";
        else if (text.contains("马铃薯") || text.contains("土豆")) targetItem = "minecraft:potato";
        if (targetItem == null) return;
        int count = text.matches("(?is).*(全部|整片|这片|一组).*" ) ? 64 : 16;
        var number = java.util.regex.Pattern.compile("(?:小麦|胡萝卜|土豆|马铃薯)\\s*([1-9][0-9]{0,2})|([1-9][0-9]{0,2})\\s*(?:个|份|组)").matcher(text);
        if (number.find()) count = Math.clamp(Integer.parseInt(number.group(1) == null ? number.group(2) : number.group(1)), 1, 256);
        targetStartCount = motor.inventoryCount(targetItem); targetCount = targetStartCount + count; targetStableTicks = 0; nextFallback = ticks;
        rememberResult("Quantity task initialized: gather " + count + " new " + targetItem + "; continue the harvest skill until inventory verifies the quota.");
    }
    private boolean startFallback(JsonObject observation) {
        String intent = control.goal(config.survivalGoal);
        int addressed = intent.indexOf("对你说：");
        if (addressed >= 0) intent = intent.substring(addressed + 4).split("\n", 2)[0];
        boolean woodRequest = intent.matches("(?is).*(采集|收集|砍|挖|弄点).*(木头|木材|原木|树).*");
        boolean cropRequest = intent.matches("(?is).*(采集|收集|收割).*(小麦|作物).*");
        boolean prohibited = (intent + config.notes).matches("(?is).*(不要|不许|禁止|不准|别).*(采集|挖|砍|破坏).*");
        if (ticks < nextFallback || ScreenPolicy.blocksWorld(mc.gui.screen()) || motor.busy()
                || mc.player.getHealth() <= 6 || prohibited
                || ((control.localControl() || control.publicGoal()) && targetItem == null && !woodRequest && !cropRequest)) return false;
        nextFallback = ticks + (targetItem == null ? 200 : 10);
        JsonArray blocks = observation.getAsJsonObject("selfAndWorld").getAsJsonArray("visibleBlocks");
        if (blocks == null) return false;
        java.util.ArrayList<BlockPos> candidates = new java.util.ArrayList<>();
        for (var value : blocks) {
            JsonObject block = value.getAsJsonObject(); BlockPos pos = position(block);
            var state = mc.level.getBlockState(pos);
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            boolean wanted = targetItem != null ? id.equals(targetItem)
                    : (woodRequest || (!control.localControl() && !control.publicGoal()))
                        && state.is(net.minecraft.tags.BlockTags.LOGS) && motor.inventoryCount(id) < 24;
            if (targetItem != null && targetItem.equals("minecraft:carrot") && state.is(net.minecraft.world.level.block.Blocks.CARROTS)) wanted = true;
            if (targetItem != null && targetItem.equals("minecraft:potato") && state.is(net.minecraft.world.level.block.Blocks.POTATOES)) wanted = true;
            if (targetItem == null && cropRequest && state.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop && crop.isMaxAge(state)) wanted = true;
            if (state.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop && !crop.isMaxAge(state)) wanted = false;
            if (wanted) candidates.add(pos);
        }
        if (targetItem != null) for (var value : motor.cropTargets(targetItem, config.scanRadius)) {
            BlockPos pos = position(value.getAsJsonObject()); if (!candidates.contains(pos)) candidates.add(pos);
        }
        candidates.sort(java.util.Comparator.comparingDouble(pos -> mc.player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos))));
        for (BlockPos pos : candidates) {
            askForMissingTool(pos);
            String result = harvest.start(pos);
            if (result.startsWith("STARTED")) {
                actions.clear(); narrationOnlyDecisions = 0;
                rememberResult("RECOVERY harvest_block: " + result + " at " + pos);
                notice("正在执行采集流程：接近资源、挖掘、拾取，再核对背包。");
                return true;
            }
            rememberResult("Recovery target " + pos + ": " + result);
        }
        if (targetItem == null && !control.localControl() && !control.publicGoal()) {
            JsonArray destinations = observation.getAsJsonObject("selfAndWorld").getAsJsonArray("safeDestinations");
            if (destinations != null) for (var value : destinations) {
                BlockPos next = position(value.getAsJsonObject()); if (recentExploration.contains(next)) continue;
                String moved = motor.moveTo(next);
                if (moved.startsWith("STARTED")) {
                    recentExploration.addLast(next); while (recentExploration.size() > 32) recentExploration.removeFirst();
                    actions.clear(); narrationOnlyDecisions = 0; rememberResult("RECOVERY exploration: " + moved); return true;
                }
            }
        }
        return false;
    }
    private void askForMissingTool(BlockPos position) {
        if (ticks < nextHelpRequest || mc.getConnection() == null) return;
        var block = mc.level.getBlockState(position); boolean axe = false, correct = false;
        for (int slot = 0; slot < 36; slot++) {
            var item = mc.player.getInventory().getItem(slot);
            axe |= item.is(net.minecraft.tags.ItemTags.AXES); correct |= item.isCorrectToolForDrops(block);
        }
        String tool = block.is(net.minecraft.tags.BlockTags.LOGS) && !axe ? "斧子" : block.requiresCorrectToolForDrops() && !correct ? "合适的镐" : "";
        if (tool.isEmpty()) return;
        String peer = mc.getConnection().getOnlinePlayers().stream().map(p -> p.getProfile().name()).filter(name -> !name.equalsIgnoreCase(mc.player.getGameProfile().name())).findFirst().orElse("");
        if (peer.isEmpty()) return;
        nextHelpRequest = ticks + 1200; helpUrgency = 1;
        say("@" + peer + " 我缺一把" + tool + "，能借我用一下吗？");
        rememberResult("SUPPLY REQUEST: asked an online player for " + tool + "; continue only feasible work and inspect actual gifts.");
    }
    private String perform(AiBrain.Action action) throws Exception {
        requireEnabled(); JsonObject args = action.args();
        modelActionActive = true;
        try { return switch (action.tool()) {
            case "move_to" -> motor.moveTo(position(args));
            case "follow_player" -> motor.follow(args.get("name").getAsString());
            case "collect_nearby" -> motor.collect();
            case "open_inventory" -> {
                String purpose = args.has("purpose") ? args.get("purpose").getAsString() : "";
                boolean explicit = (control.localControl() || control.publicGoal()) && control.goal("").matches("(?is).*(整理|合成|制作|装备|穿上|背包).*" );
                int occupied = 0; for (int slot = 0; slot < 36; slot++) if (!mc.player.getInventory().getItem(slot).isEmpty()) occupied++;
                String rejection = inventoryPolicy.rejection(purpose, ticks, explicit, occupied >= 32);
                if (rejection != null) yield rejection;
                if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen) yield "SKIPPED: preserve the owner's current chat draft; craft after typing ends";
                inventoryPolicy.opened(ticks); mc.gui.setScreen(new InventoryScreen(mc.player));
                yield "OPENED: perform the requested " + purpose + " operations, then close; counts do not need reopening";
            }
            case "dig_block" -> motor.dig(position(args));
            case "harvest_block" -> harvest.start(position(args));
            case "collect_resource" -> {
                String item = args.get("item").getAsString(); int count = args.get("count").getAsInt();
                if (!net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(Identifier.parse(item)) || count < 1 || count > 256) yield "FAILED: use a known item id and count 1–256";
                targetItem = item; targetStartCount = motor.inventoryCount(item); targetCount = targetStartCount + count; targetStableTicks = 0; nextFallback = ticks;
                yield "STARTED: continuous collection of " + count + " new " + item + "; verify stable inventory quota";
            }
            case "craft_basic" -> motor.busy() || harvest.busy() ? "FAILED: finish the physical task first" : basicCraft.start(args.get("recipe").getAsString());
            case "craft_workbench" -> motor.busy() || harvest.busy() || basicCraft.busy() || forage.busy() ? "FAILED: finish the active skill first" : workbench.start(args.get("recipe").getAsString());
            case "forage_food" -> motor.busy() || harvest.busy() || basicCraft.busy() || workbench.busy() ? "FAILED: finish the active skill first" : forage.start();
            case "place_block" -> motor.place(position(args), args.get("face").getAsString());
            case "select_hotbar" -> motor.select(args.get("slot").getAsInt());
            case "eat" -> motor.eat();
            case "use_healing_item" -> motor.useHealingItem();
            case "use_block" -> motor.useBlock(position(args));
            case "attack_nearest" -> motor.attack();
            case "escape" -> motor.escape();
            case "ui_click_slot" -> ui.clickSlot(args.get("slot").getAsInt(), args.get("button").getAsString(), args.get("revision").getAsLong());
            case "ui_click_chat" -> ui.clickChat(args.get("id").getAsInt());
            case "ui_input_chat" -> ui.inputChat(args.get("text").getAsString());
            case "ui_widget" -> {
                if (ui.snapshot().get("revision").getAsLong() != args.get("revision").getAsLong()) yield "FAILED: stale UI revision";
                yield ui.screenAction(args.get("index").getAsInt(), args.get("value").getAsString());
            }
            case "ui_close" -> mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen || mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen
                    ? "SKIPPED: the owner's local overlay remains open; world controls are available" : ui.closeUi();
            case "observe" -> {
                String area = args.get("area").getAsString().toLowerCase(java.util.Locale.ROOT);
                if (!java.util.Set.of("world", "inventory", "hud", "chat", "menu").contains(area)) yield "FAILED: unknown observation area";
                requestedObservation.add(area); nextDecision = ticks;
                yield "OK: next planner view will include " + area;
            }
            case "inspect_slot" -> {
                int slot = args.get("slot").getAsInt();
                if (slot < 0 || mc.player == null || slot >= mc.player.containerMenu.slots.size()) yield "FAILED: invalid observed slot";
                inspectedSlot = slot; requestedObservation.add("menu"); nextDecision = ticks;
                yield "OK: requested detailed slot " + slot;
            }
            case "chat_say" -> {
                yield say(args.get("text").getAsString(), !finishConversationAfterReply);
            }
            case "wait" -> { nextDecision = ticks + Math.clamp(args.get("seconds").getAsInt(), 1, 30) * 20L; yield "WAITING for observations"; }
            case "complete_goal" -> {
                if (targetItem != null) yield "FAILED: item goal is completed only by the stable inventory count check";
                if (!control.localControl() && !control.publicGoal()) yield "SKIPPED: autonomous survival is ongoing; execute the next concrete skill instead";
                control.complete(); motor.stop(); actions.clear(); yield "Model reports goal ended; this generic goal has no automatic predicate: " + args.get("evidence").getAsString();
            }
            case "remember" -> {
                String note = args.get("text").getAsString(); if (note.length() > 400) yield "FAILED: note too long";
                if (note.matches("(?is).*(item_display|物品展示|展示方块|展示实体).*(无法|不能|不是真的|没有真正|假的).*"))
                    yield "FAILED: entity type cannot prove that plugin crops are fake or unharvestable. Only persist verified interaction/item outcomes.";
                config.notes = (config.notes + "\n" + note).strip(); if (config.notes.length() > 4000) config.notes = config.notes.substring(config.notes.length() - 4000);
                config.save(); yield "OK: note saved";
            }
            case "learn_skill" -> skillBook == null ? "FAILED: skill library is unavailable"
                    : skillBook.learn(serverKey(), args.get("topic").getAsString(), args.get("knowledge").getAsString());
            default -> "FAILED: unknown tool";
        }; } finally { modelActionActive = false; }
    }
    public int command(String command, String value) {
        try {
            if (modelActionActive) throw new IllegalStateException("模型和公共任务不能调用本地控制命令");
            switch (command) {
                case "enable" -> enable();
                case "disable" -> disable();
                case "stop" -> stop();
                case "goal" -> takeLocalControl(value);
                case "release" -> { requireEnabled(); targetItem = null; manualMoveTarget = null; control.releaseLocal(); invalidateRequest(); motor.stop(); nextDecision = ticks; notice("本地任务控制已释放。"); }
                case "auto" -> { requireEnabled(); if (!value.equals("on") && !value.equals("off")) throw new IllegalArgumentException("使用 on 或 off"); control.autonomous(value.equals("on")); invalidateRequest(); motor.stop(); notice("自主规划：" + value); }
                case "status" -> notice(status());
                case "inspect" -> {
                    requireConnected(); Path file = FabricLoader.getInstance().getConfigDir().resolve("mc-ai-partner-observation.json");
                    Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(observation()), StandardCharsets.UTF_8); notice("观察数据已写入 " + file);
                }
                case "reload" -> { boolean wasEnabled = control.enabled(); disable(); configurationReady = false; config = PartnerConfig.load(); configurationReady = true; motor = new ClientMotor(mc, config.navigationRange); harvest = new HarvestSkill(mc, motor); basicCraft = new BasicCraftSkill(mc); workbench = new WorkbenchSkill(mc, motor); forage = new ForageSkill(mc, motor); if (wasEnabled) enable(); notice("配置已重载。"); }
                case "thinking", "reasoningprotocol" -> {
                    if (command.equals("thinking") && !value.equals("on") && !value.equals("off"))
                        throw new IllegalArgumentException("推理开关使用 on 或 off");
                    if (command.equals("reasoningprotocol")) dev.mcai.partner.brain.ReasoningProtocol.fromId(value);
                    disable();
                    if (command.equals("thinking")) config.enableThinking = value.equals("on");
                    else config.reasoningProtocol = value;
                    config.save(); notice("推理设置已保存，开始接管后生效。");
                }
                case "model", "endpoint", "keyenv" -> {
                    disable(); if (command.equals("model")) config.model = value; else if (command.equals("endpoint")) config.baseUrl = value; else config.apiKeyEnv = value;
                    config.save(); notice("连接设置已保存，开始接管后生效。");
                }
                default -> throw new IllegalArgumentException("未知本地命令");
            }
            return 1;
        } catch (Exception e) { notice("操作失败：" + e.getMessage()); return 0; }
    }
}
