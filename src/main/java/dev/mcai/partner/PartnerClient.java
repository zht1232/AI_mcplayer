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
        ui = new UiBridge(mc); motor = new ClientMotor(mc, config.navigationRange);
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("mc_ai_partner", "controls"));
        panelKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mc_ai_partner.panel", GLFW.GLFW_KEY_F8, category));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            control.connect(); ui.clear(); feedback.clear(); actions.clear(); recentSpeech.clear();
            notice("已连接。F8 打开面板；/aip enable 开启当前账号的 AI。");
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { control.disconnect(); invalidate(); motor.active(false); ui.clear(); });
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
                if (recentSpeech.stream().anyMatch(spoken -> ticks - spoken.tick() <= 100
                        && (line.equals(spoken.text()) || line.endsWith(spoken.text())))) return;
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
                    targetItem = item; targetCount = motor.inventoryCount(item) + count; targetStableTicks = 0;
                    return 1;
                }))))
                .then(literal("status").executes(ctx -> command("status", "")))
                .then(literal("inspect").executes(ctx -> command("inspect", "")))
                .then(literal("reload").executes(ctx -> command("reload", "")))
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
    public String status() {
        String activity = pending == null ? motor.status() : "思考中 " + (System.nanoTime() - requestStartedNanos) / 1_000_000_000L + " 秒";
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
        brain = new AiBrain(new AiBrain.Options(config.baseUrl, config.model, config.apiKeyEnv, config.maxTokens, config.timeoutSeconds), ToolCatalog.schemas());
        control.enable(); control.autonomous(true); motor.active(true); nextDecision = ticks; lastError = "";
        notice("拾野开始接管。提到或 @" + mc.player.getGameProfile().name() + " 可交流；本地控制优先。");
    }
    public void disable() { control.disable(); targetItem = null; manualMoveTarget = null; invalidate(); motor.active(false); notice("拾野已暂停。"); }
    public void stop() { control.stop(); targetItem = null; manualMoveTarget = null; invalidateRequest(); motor.stop(); notice("已停止任务和自主规划。/aip auto on 恢复自主。"); }
    public void takeLocalControl(String goal) {
        requireEnabled(); targetItem = null; manualMoveTarget = null; control.submit(goal, ControlState.Priority.LOCAL); invalidateRequest(); motor.stop(); nextDecision = ticks;
        rememberResult("New local instruction: " + goal);
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
            if (old != control.generation()) { invalidateRequest(); motor.stop(); nextDecision = ticks; }
            else if (control.localControl()) say("收到，我先完成当前的本地安排，再处理你的消息。");
            rememberResult("Public player instruction accepted from " + message.speaker() + ": " + message.text());
        }
    }
    private void invalidateRequest() {
        // cancel() can invoke completion callbacks inline on this client thread.
        // Invalidate their captured revision before cancelling the old future.
        memoryRevision++;
        CompletableFuture<AiBrain.Decision> obsolete = pending;
        pending = null;
        actions.clear();
        requestedObservation.clear(); inspectedSlot = null;
        finishConversationAfterReply = false;
        nextDecision = ticks;
        if (obsolete != null) obsolete.cancel(true);
    }
    private void invalidate() { invalidateRequest(); if (brain != null) brain.close(); brain = null; }
    private void rememberResult(String result) { feedback.addLast(result); while (feedback.size() > 12) feedback.removeFirst(); LOG.info("Action feedback: {}", result); }
    public void notice(String text) { lastSpeech = text; LOG.info("{}", text); }
    /** Natural speech uses normal signed player chat; local status and errors stay in notice(). */
    private String say(String value) {
        if (value == null || value.isBlank()) return "FAILED: speech is empty";
        String text = value.replaceAll("[\\r\\n\\t]+", " ").strip();
        if (text.startsWith("/") || text.startsWith(config.publicCommandPrefix.strip())) return "FAILED: speech must not be a command";
        if (text.length() > 256) text = text.substring(0, 256);
        String speech = text;
        if (recentSpeech.stream().anyMatch(previous -> ticks - previous.tick() < 400 && previous.text().equals(speech)))
            return "SKIPPED: the same speech was sent recently";
        if (!recentSpeech.isEmpty() && ticks - recentSpeech.getLast().tick() < 40)
            return "SKIPPED: wait before sending another public chat message";
        String result = ui.inputChat(text);
        if (result.startsWith("已发送")) {
            recentSpeech.addLast(new Speech(text, ticks));
            while (recentSpeech.size() > 8) recentSpeech.removeFirst();
            lastSpeech = text;
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
        if (mc.gui.screen() != null && motor.busy()) {
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
            rememberResult(result);
            if (manualMoveTarget != null && result.startsWith("FAILED")) { manualMoveTarget = null; control.complete(); notice(result); }
        }
        if (manualMoveTarget != null) {
            movementStableTicks = mc.player.position().distanceTo(manualMoveTarget) < .7 ? movementStableTicks + 1 : 0;
            if (movementStableTicks >= 20) { manualMoveTarget = null; control.complete(); motor.stop(); notice("本地移动已到达并稳定停下。"); }
            return; // A deterministic local movement does not need an LLM request.
        }
        if (targetItem != null) {
            targetStableTicks = motor.inventoryCount(targetItem) >= targetCount ? targetStableTicks + 1 : 0;
            if (targetStableTicks >= 20) {
                notice("采集数量已通过背包检查：" + targetItem + " × " + motor.inventoryCount(targetItem));
                targetItem = null; control.complete(); invalidateRequest(); motor.stop(); nextDecision = ticks + 20;
            }
        }
        if (!motor.busy() && !actions.isEmpty()) {
            if (actionGeneration != control.generation()) { actions.clear(); return; }
            AiBrain.Action action = actions.removeFirst();
            try {
                String result = perform(action);
                rememberResult(action.tool() + ": " + result);
                if (finishConversationAfterReply && control.publicGoal() && action.tool().equals("chat_say") && result.startsWith("已发送")) {
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
            finishConversationAfterReply = control.publicGoal() && observation.get("observationMode").getAsString().equals("conversation")
                    && decision.actions().stream().anyMatch(action -> action.tool().equals("chat_say"))
                    && decision.actions().stream().allMatch(action -> action.tool().equals("chat_say") || action.tool().equals("complete_goal"));
            if (!decision.speech().isBlank()) say(decision.speech());
            if (control.publicGoal() && observation.get("observationMode").getAsString().equals("conversation")
                    && !decision.speech().isBlank() && decision.actions().isEmpty()) {
                control.complete();
                rememberResult("Conversation answered; proceeding to the next queued instruction.");
            }
            actions.addAll(decision.actions()); actionGeneration = generation;
        }));
    }
    private BlockPos position(JsonObject args) { return new BlockPos(args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt()); }
    private String perform(AiBrain.Action action) throws Exception {
        requireEnabled(); JsonObject args = action.args();
        modelActionActive = true;
        try { return switch (action.tool()) {
            case "move_to" -> motor.moveTo(position(args));
            case "follow_player" -> motor.follow(args.get("name").getAsString());
            case "collect_nearby" -> motor.collect();
            case "open_inventory" -> { mc.gui.setScreen(new InventoryScreen(mc.player)); yield "OPENED: inspect current inventory slots"; }
            case "dig_block" -> motor.dig(position(args));
            case "place_block" -> motor.place(position(args), args.get("face").getAsString());
            case "select_hotbar" -> motor.select(args.get("slot").getAsInt());
            case "eat" -> motor.eat();
            case "use_block" -> motor.useBlock(position(args));
            case "attack_nearest" -> motor.attack();
            case "ui_click_slot" -> ui.clickSlot(args.get("slot").getAsInt(), args.get("button").getAsString(), args.get("revision").getAsLong());
            case "ui_click_chat" -> ui.clickChat(args.get("id").getAsInt());
            case "ui_input_chat" -> ui.inputChat(args.get("text").getAsString());
            case "ui_widget" -> {
                if (ui.snapshot().get("revision").getAsLong() != args.get("revision").getAsLong()) yield "FAILED: stale UI revision";
                yield ui.screenAction(args.get("index").getAsInt(), args.get("value").getAsString());
            }
            case "ui_close" -> ui.closeUi();
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
                yield say(args.get("text").getAsString());
            }
            case "wait" -> { nextDecision = ticks + Math.clamp(args.get("seconds").getAsInt(), 1, 30) * 20L; yield "WAITING for observations"; }
            case "complete_goal" -> {
                if (targetItem != null) yield "FAILED: item goal is completed only by the stable inventory count check";
                control.complete(); motor.stop(); actions.clear(); yield "Model reports goal ended; this generic goal has no automatic predicate: " + args.get("evidence").getAsString();
            }
            case "remember" -> {
                String note = args.get("text").getAsString(); if (note.length() > 400) yield "FAILED: note too long";
                config.notes = (config.notes + "\n" + note).strip(); if (config.notes.length() > 4000) config.notes = config.notes.substring(config.notes.length() - 4000);
                config.save(); yield "OK: note saved";
            }
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
                case "reload" -> { boolean wasEnabled = control.enabled(); disable(); configurationReady = false; config = PartnerConfig.load(); configurationReady = true; motor = new ClientMotor(mc, config.navigationRange); if (wasEnabled) enable(); notice("配置已重载。"); }
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
