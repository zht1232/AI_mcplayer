package dev.mcai.partner;

import com.google.gson.JsonObject;
import dev.mcai.partner.ui.UiBridge;
import java.util.Properties;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import java.util.List;
import java.util.Optional;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.BossEvent;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Isolated real client/server UI test. It never calls an LLM or connects to the user's server. */
public final class PartnerUiGameTest implements FabricClientGameTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    @Override public void runTest(ClientGameTestContext context) {
        if (NaturalSurvivalSoak.minutes() > 0) { NaturalSurvivalSoak.run(context); return; }
        context.runOnClient(mc -> {
            check(PartnerClient.instance() != null, "client entrypoint did not load");
            check(!PartnerClient.instance().enabled(), "AI must start disabled");
            check(PartnerClient.instance().command("enable", "") == 0, "AI must not enable before joining a server");
        });
        Properties properties = new Properties(); properties.setProperty("online-mode", "false");
        properties.setProperty("level-type", "minecraft:flat"); properties.setProperty("spawn-protection", "0");
        properties.setProperty("enforce-secure-profile", "false");
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(properties);
             TestDedicatedServerConnection connection = server.connect()) {
            connection.waitForChunksDownload();
            context.runOnClient(mc -> check(!PartnerClient.instance().enabled(), "joining must not automatically enable AI"));
            server.runCommand("scoreboard objectives add mc_ai_shop trigger");
            server.runCommand("scoreboard players enable @a mc_ai_shop");
            server.runOnServer(mcServer -> {
                var player = connection.getServerPlayer();
                SimpleContainer inventory = new SimpleContainer(27);
                ItemStack stack = new ItemStack(Items.OAK_LOG, 16);
                stack.set(DataComponents.CUSTOM_NAME, Component.literal("测试原木"));
                inventory.setItem(0, stack);
                player.openMenu(new SimpleMenuProvider((id, items, p) -> ChestMenu.threeRows(id, items, inventory), Component.literal("AI 测试箱子菜单")));
                ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("测试生存状态"), BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
                bar.setProgress(.5f); bar.addPlayer(player);
                player.sendSystemMessage(Component.literal("商店测试按钮").withStyle(Style.EMPTY
                        .withClickEvent(new ClickEvent.RunCommand("/trigger mc_ai_shop"))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击验证聊天动作")))));
            });
            connection.waitForClientboundPackets(); context.waitForScreen(ContainerScreen.class);
            JsonObject ui = context.computeOnClient(mc -> PartnerClient.instance().uiBridge().snapshot());
            check(ui.getAsJsonObject("container").getAsJsonArray("slots").get(0).toString().contains("测试原木"), "item name not observed");
            check(ui.toString().contains("测试生存状态"), "BossBar was not observed through runtime Mixin");
            check(ui.getAsJsonArray("chatActions").size() > 0, "clickable chat component was lost");
            long revision = ui.get("revision").getAsLong();
            context.runOnClient(mc -> {
                UiBridge bridge = PartnerClient.instance().uiBridge();
                check(bridge.clickSlot(0, "LEFT", revision - 1).startsWith("失败"), "stale UI click was accepted");
                check(bridge.clickSlot(0, "RIGHT", revision).startsWith("已发送"), "normal right click failed");
            });
            connection.waitForServerboundPackets(); connection.waitForClientboundPackets();
            server.runOnServer(mcServer -> check(connection.getServerPlayer().containerMenu.getCarried().getCount() == 8, "server did not receive right-click split"));
            context.runOnClient(mc -> {
                UiBridge bridge = PartnerClient.instance().uiBridge();
                bridge.clickSlot(0, "LEFT", bridge.snapshot().get("revision").getAsLong());
            });
            connection.waitForServerboundPackets(); connection.waitForClientboundPackets();
            context.runOnClient(mc -> {
                UiBridge bridge = PartnerClient.instance().uiBridge();
                bridge.clickSlot(0, "SHIFT_LEFT", bridge.snapshot().get("revision").getAsLong());
            });
            connection.waitForServerboundPackets(); connection.waitForClientboundPackets();
            server.runOnServer(mcServer -> check(connection.getServerPlayer().getInventory().countItem(Items.OAK_LOG) == 16, "Shift click did not transfer real server items"));
            context.takeScreenshot("mc-ai-partner-container");
            context.runOnClient(mc -> {
                UiBridge bridge = PartnerClient.instance().uiBridge(); bridge.closeUi();
                int id = bridge.snapshot().getAsJsonArray("chatActions").get(0).getAsJsonObject().get("id").getAsInt();
                bridge.clickChat(id);
            });
            connection.waitForServerboundPackets(); connection.waitForClientboundPackets();
            server.runOnServer(mcServer -> {
                var score = mcServer.getScoreboard().getPlayerScoreInfo(connection.getServerPlayer(), mcServer.getScoreboard().getObjective("mc_ai_shop"));
                check(score != null && score.value() == 1, "chat click did not execute the real server command");
            });
            server.runCommand("scoreboard objectives add mc_ai_dialog trigger");
            server.runCommand("scoreboard objectives add mc_ai_text trigger");
            server.runCommand("scoreboard players enable @a mc_ai_text");
            context.runOnClient(mc -> check(PartnerClient.instance().uiBridge().inputChat("/trigger mc_ai_text").startsWith("已发送"), "typed command was not sent"));
            connection.waitForServerboundPackets(); connection.waitForClientboundPackets();
            server.runOnServer(mcServer -> {
                var score = mcServer.getScoreboard().getPlayerScoreInfo(connection.getServerPlayer(), mcServer.getScoreboard().getObjective("mc_ai_text"));
                check(score != null && score.value() == 1, "typed chat command did not reach the server");
            });
            server.runCommand("scoreboard players enable @a mc_ai_dialog");
            server.runOnServer(mcServer -> connection.getServerPlayer().openDialog(Holder.direct(new NoticeDialog(
                    new CommonDialogData(Component.literal("AI Dialog 测试"), Optional.empty(), true, false, DialogAction.CLOSE, List.of(), List.of()),
                    new ActionButton(new CommonButtonData(Component.literal("提交测试"), 150), Optional.of(new StaticAction(new ClickEvent.RunCommand("/trigger mc_ai_dialog"))))))));
            connection.waitForClientboundPackets(); context.waitForScreen(DialogScreen.class);
            context.runOnClient(mc -> {
                UiBridge bridge = PartnerClient.instance().uiBridge(); JsonObject view = bridge.snapshot();
                check(view.has("dialog"), "Dialog definition was lost");
                int button = -1;
                for (var widget : view.getAsJsonArray("widgets")) if (widget.toString().contains("提交测试")) button = widget.getAsJsonObject().get("index").getAsInt();
                check(button >= 0, "Dialog action widget was not observed");
                bridge.screenAction(button, "");
            });
            connection.waitForServerboundPackets(); connection.waitForClientboundPackets();
            server.runOnServer(mcServer -> {
                var score = mcServer.getScoreboard().getPlayerScoreInfo(connection.getServerPlayer(), mcServer.getScoreboard().getObjective("mc_ai_dialog"));
                check(score != null && score.value() == 1, "Dialog click did not execute the server action");
            });
            testPanelCancellation(context, server, connection);
            testNoActionRecovery(context, server, connection);
            context.runOnClient(mc -> {
                PartnerClient partner = PartnerClient.instance();
                check(partner.command("enable", "") == 1, "entered multiplayer session should allow activation");
                partner.command("auto", "off"); // Same client task; no decision tick and no model request.
            });
            context.runOnClient(mc -> PartnerClient.instance().motor().follow("MissingFollowTarget"));
            context.waitFor(mc -> !PartnerClient.instance().motor().busy(), 50);
            // The model must have a genuine movement choice on a plain field with no
            // interesting resource blocks. Walk to one of the actual supplied positions.
            BlockPos destination = context.computeOnClient(mc -> {
                var positions = PartnerClient.instance().motor().snapshot(6).getAsJsonArray("safeDestinations");
                check(!positions.isEmpty() && positions.size() <= 8, "plain terrain must expose bounded safe walking choices");
                JsonObject position = positions.get(0).getAsJsonObject();
                check(position.get("distance").getAsDouble() >= 1.5 && position.get("pathSteps").getAsInt() > 0, "walking choice must actually leave current position");
                return new BlockPos(position.get("x").getAsInt(), position.get("y").getAsInt(), position.get("z").getAsInt());
            });
            context.runOnClient(mc -> check(PartnerClient.instance().moveManually(destination).startsWith("STARTED"), "short safe movement failed to plan"));
            context.runOnClient(mc -> mc.gui.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true)));
            context.waitFor(mc -> mc.player.position().distanceTo(new net.minecraft.world.phys.Vec3(destination.getX() + .5, destination.getY(), destination.getZ() + .5)) < .7, 180);
            context.waitTicks(25); connection.waitForServerboundPackets();
            server.runOnServer(mcServer -> check(connection.getServerPlayer().position().distanceTo(new net.minecraft.world.phys.Vec3(destination.getX() + .5, destination.getY(), destination.getZ() + .5)) < .9, "server did not accept normal movement"));
            context.runOnClient(mc -> check(mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen, "Esc overlay closed during movement"));
            BlockPos log = destination.offset(1, 0, 0);
            server.runOnServer(mcServer -> connection.getServerLevel().setBlockAndUpdate(log, net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState()));
            connection.waitForClientboundPackets();
            context.runOnClient(mc -> check(PartnerClient.instance().motor().dig(log).startsWith("STARTED"), "visible log mining did not start"));
            context.waitFor(mc -> !PartnerClient.instance().motor().busy(), 320);
            server.runOnServer(mcServer -> check(connection.getServerLevel().getBlockState(log).isAir(), "server did not confirm the mined block"));
            context.runOnClient(mc -> {
                check(mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen, "Esc overlay closed during mining");
                mc.gui.setScreen(null);
            });
            CombatReflexGameTest.run(context, server, connection);
            SurvivalGameTest.run(context, server, connection);
            WorkbenchSkillGameTest.run(context, server, connection);
            ForageSkillGameTest.run(context, server, connection);
            context.runOnClient(mc -> {
                PartnerClient partner = PartnerClient.instance();
                partner.command("disable", "");
                partner.config().enableThinking = false;
                partner.config().reasoningProtocol = "auto";
                partner.openPanel();
                mc.gui.toastManager().clear();
            });
            context.takeScreenshot("mc-ai-partner-panel");
            context.clickScreenButton("连接");
            context.clickScreenButton("推理：关");
            context.clickScreenButton("兼容：自动");
            context.runOnClient(mc -> {
                var field = mc.gui.screen().children().stream()
                        .filter(widget -> widget instanceof net.minecraft.client.gui.components.EditBox box && box.getMessage().getString().equals("API 密钥"))
                        .map(widget -> (net.minecraft.client.gui.components.EditBox)widget).findFirst().orElseThrow();
                field.setValue("sk-ui-test-secret");
                check(!PartnerClient.instance().uiBridge().snapshot().toString().contains("sk-ui-test-secret"), "local secret entered an exported UI observation");
            });
            context.clickScreenButton("保存连接");
            context.runOnClient(mc -> {
                try {
                    var saved = PartnerConfig.load();
                    check(saved.enableThinking && saved.reasoningProtocol.equals("llama"), "panel did not persist thinking/protocol settings");
                    check(saved.apiKey.equals("sk-ui-test-secret") && saved.apiKeyEnv.isEmpty(), "panel did not persist direct API key");
                } catch (java.io.IOException error) { throw new RuntimeException(error); }
            });
            context.clickScreenButton("推理：开");
            context.runOnClient(mc -> mc.gui.screen().children().stream()
                    .filter(widget -> widget instanceof net.minecraft.client.gui.components.EditBox box && box.getMessage().getString().equals("API 密钥"))
                    .map(widget -> (net.minecraft.client.gui.components.EditBox)widget).findFirst().orElseThrow().setValue(""));
            context.clickScreenButton("保存连接");
            context.runOnClient(mc -> {
                try {
                    var saved = PartnerConfig.load();
                    check(!saved.enableThinking, "panel did not persist disabled thinking");
                    check(saved.apiKey.isEmpty() && saved.apiKeyEnv.isEmpty(), "clearing the input did not clear the stored credential");
                }
                catch (java.io.IOException error) { throw new RuntimeException(error); }
            });
            context.takeScreenshot("mc-ai-partner-model-settings");
        }
        context.runOnClient(mc -> check(!PartnerClient.instance().enabled(), "disconnect did not stop AI"));
    }

    /** A plan-only model must neither spam public chat nor leave autonomous recovery idle. */
    private void testNoActionRecovery(ClientGameTestContext context, TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
        String originalUrl = context.computeOnClient(mc -> PartnerClient.instance().config().baseUrl);
        AtomicInteger requests = new AtomicInteger();
        server.runOnServer(mcServer -> { connection.getServerPlayer().getInventory().clearContent(); connection.getServerPlayer().inventoryMenu.broadcastChanges(); });
        connection.waitForClientboundPackets();
        BlockPos resource = context.computeOnClient(mc -> mc.player.blockPosition().offset(6, 0, 0));
        int initialLogs = context.computeOnClient(mc -> PartnerClient.instance().motor().inventoryCount("minecraft:oak_log"));
        server.runOnServer(mcServer -> connection.getServerLevel().setBlockAndUpdate(resource, net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState()));
        connection.waitForClientboundPackets();
        try {
            HttpServer model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            model.createContext("/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                String text = "REPEATED_WORK_PLAN_" + requests.incrementAndGet() + " 我看到周围有木头，先收集木材，然后检查食物。";
                byte[] response = ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"" + text + "\"}}]}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
            });
            model.start();
            try {
                context.runOnClient(mc -> {
                    var partner = PartnerClient.instance(); partner.command("disable", "");
                    partner.config().baseUrl = "http://127.0.0.1:" + model.getAddress().getPort() + "/v1";
                    check(partner.command("enable", "") == 1, "plan-only fixture activation failed");
                    mc.gui.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
                });
                context.waitFor(mc -> mc.level.getBlockState(resource).isAir()
                        && (PartnerClient.instance().motor().inventoryCount("minecraft:oak_log") > initialLogs
                        || PartnerClient.instance().motor().inventoryCount("minecraft:oak_planks") > 0), 700);
                connection.waitForServerboundPackets();
                server.runOnServer(mcServer -> check(connection.getServerLevel().getBlockState(resource).isAir(), "plan-only recovery never mined the observed resource"));
                context.runOnClient(mc -> {
                    check(requests.get() <= 1, "basic autonomous work waited for repeated model decisions");
                    check(!PartnerClient.instance().uiBridge().snapshot().getAsJsonArray("chat").toString().contains("REPEATED_WORK_PLAN_"), "work narration leaked into public chat");
                    check(mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen, "recovery closed the user's Esc overlay");
                });
            } finally {
                context.runOnClient(mc -> { PartnerClient.instance().command("disable", ""); PartnerClient.instance().config().baseUrl = originalUrl; mc.gui.setScreen(null); });
                model.stop(0);
            }
        } catch (java.io.IOException error) { throw new RuntimeException(error); }
    }

    /** A delayed local mock lets cancel() exercise the real inline client callback. */
    private void testPanelCancellation(ClientGameTestContext context, TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        String originalUrl = context.computeOnClient(mc -> PartnerClient.instance().config().baseUrl);
        String originalModel = context.computeOnClient(mc -> PartnerClient.instance().config().model);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            HttpServer model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            model.setExecutor(executor);
            model.createContext("/v1/chat/completions", exchange -> {
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (requests.incrementAndGet() == 1) {
                    firstStarted.countDown();
                    try { releaseFirst.await(15, TimeUnit.SECONDS); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                }
                JsonObject payload = com.google.gson.JsonParser.parseString(requestBody).getAsJsonObject();
                JsonObject input = com.google.gson.JsonParser.parseString(payload.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString()).getAsJsonObject();
                boolean chatOnly = input.get("goal").getAsString().contains("__chat_tool_case__");
                String json = chatOnly
                        ? "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"type\":\"function\",\"function\":{\"name\":\"chat_say\",\"arguments\":\"{\\\"text\\\":\\\"CHAT_TOOL_ONLY_OK\\\"}\"}}]}}]}"
                        : "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"PANEL_REPLAN_OK\"}}]}";
                byte[] response = json.getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                } catch (java.io.IOException cancelled) {
                    // The first client request is deliberately cancelled by opening F8.
                } finally { exchange.close(); }
            });
            model.start();
            try {
                String mockUrl = "http://127.0.0.1:" + model.getAddress().getPort() + "/v1";
                context.runOnClient(mc -> {
                    PartnerClient partner = PartnerClient.instance();
                    partner.config().baseUrl = mockUrl;
                    partner.config().model = "cancellation-test";
                    check(partner.command("enable", "") == 1, "mock brain activation failed");
                    partner.command("auto", "off");
                    partner.takeLocalControl("你好 __panel_reply__");
                });
                context.waitFor(mc -> firstStarted.getCount() == 0, 150);
                context.runOnClient(mc -> {
                    PartnerClient partner = PartnerClient.instance();
                    partner.openPanel();
                    check(partner.lastError().isEmpty(), "opening F8 misreported intentional cancellation as model failure");
                    check(!partner.lastSpeech().contains("CancellationException"), "cancel exception was displayed to the player");
                });
                server.runOnServer(mcServer -> connection.getServerPlayer().sendSystemMessage(Component.literal("Guest: @" + connection.getServerPlayer().getGameProfile().name() + " 你好 __panel_reply__")));
                connection.waitForClientboundPackets();
                releaseFirst.countDown();
                context.runOnClient(mc -> mc.gui.setScreen(null));
                context.waitFor(mc -> requests.get() >= 2 && PartnerClient.instance().lastSpeech().equals("PANEL_REPLAN_OK"), 150);
                context.waitFor(mc -> PartnerClient.instance().uiBridge().snapshot().getAsJsonArray("chat")
                        .toString().contains("PANEL_REPLAN_OK"), 100);
                context.runOnClient(mc -> check(PartnerClient.instance().lastError().isEmpty(), "replanning failed after panel cancellation"));
                context.waitTicks(50);
                server.runOnServer(mcServer -> connection.getServerPlayer().sendSystemMessage(Component.literal("Guest: @" + connection.getServerPlayer().getGameProfile().name() + " __chat_tool_case__")));
                connection.waitForClientboundPackets();
                context.waitFor(mc -> PartnerClient.instance().lastSpeech().equals("CHAT_TOOL_ONLY_OK"), 150);
                context.runOnClient(mc -> check(!PartnerClient.instance().publicTaskActive(), "chat_say-only reply kept its public task active"));
            } finally {
                releaseFirst.countDown();
                context.runOnClient(mc -> {
                    PartnerClient partner = PartnerClient.instance(); partner.command("disable", "");
                    partner.config().baseUrl = originalUrl; partner.config().model = originalModel;
                });
                model.stop(0);
            }
        } catch (java.io.IOException exception) { throw new RuntimeException(exception); }
    }
}
