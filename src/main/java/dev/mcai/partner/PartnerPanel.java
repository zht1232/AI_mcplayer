package dev.mcai.partner;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.net.URI;

/** Local controls; opening this screen suspends AI decisions and movement. */
public final class PartnerPanel extends Screen {
    private static final int TEXT = 0xFFEAF1F7;
    private static final int MUTED = 0xFF9CAFBF;
    private static final int ACCENT = 0xFF65D9C0;
    private static final int ERROR = 0xFFFFA29C;
    private final PartnerClient partner;
    private boolean settings;
    private int left, top, panelWidth, panelHeight, innerWidth, bodyTop, footerTop;
    private EditBox goal, endpoint, model, keyEnv;
    private Button enableButton, sendButton, stopButton, autoButton, releaseButton;
    private String goalDraft = "";
    private String endpointDraft, modelDraft, keyDraft;
    private String settingsMessage = "";
    private boolean settingsError;

    public PartnerPanel(PartnerClient partner) {
        super(Component.literal("AI 生存伙伴"));
        this.partner = partner;
        endpointDraft = partner.config().baseUrl;
        modelDraft = partner.config().model;
        keyDraft = partner.config().apiKeyEnv;
    }

    @Override protected void init() {
        panelWidth = Math.min(620, width - 20);
        panelHeight = Math.min(332, height - 16);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        innerWidth = panelWidth - 28;
        bodyTop = top + 68;
        footerTop = top + panelHeight - 26;
        goal = endpoint = model = keyEnv = null;
        enableButton = sendButton = stopButton = autoButton = releaseButton = null;

        button("任务与状态", left + 14, top + 43, 94, settings ? Tone.NORMAL : Tone.SELECTED,
                "下达本地任务、管理自主生存、查看运行状态", b -> switchTab(false));
        button("模型连接", left + 114, top + 43, 94, settings ? Tone.SELECTED : Tone.NORMAL,
                "编辑本地模型或 API 的连接地址", b -> switchTab(true));
        if (settings) initSettings(); else initDashboard();

        button("导出观察", left + panelWidth - 202, footerTop, 90, Tone.NORMAL,
                "导出服务器 UI 与世界观察数据，方便定位菜单问题", b -> partner.command("inspect", ""));
        button("返回游戏", left + panelWidth - 106, footerTop, 92, Tone.NORMAL,
                "关闭面板；已开启的 AI 会继续工作", b -> onClose());
        if (!settings) setInitialFocus(goal);
    }

    private void initDashboard() {
        int sendWidth = 78;
        goal = edit("本地任务", left + 14, bodyTop + 16, innerWidth - sendWidth - 8, 512, goalDraft);
        goal.setHint(Component.literal("例如：收集附近的原木"));
        goal.setResponder(value -> goalDraft = value);
        sendButton = button("下达任务", left + panelWidth - 14 - sendWidth, bodyTop + 16, sendWidth,
                Tone.PRIMARY, "本地任务优先；输入后也可按 Enter", b -> submitGoal());

        int gap = 6;
        int buttonWidth = (innerWidth - gap * 3) / 4;
        int row = bodyTop + 48;
        enableButton = button(partner.enabled() ? "关闭 AI" : "开启 AI", left + 14, row, buttonWidth,
                Tone.PRIMARY, "控制当前登录账号；退出服务器自动关闭", b -> {
                    partner.command(partner.enabled() ? "disable" : "enable", "");
                    refreshAvailability();
                });
        stopButton = button("停止任务", left + 14 + (buttonWidth + gap), row, buttonWidth,
                Tone.DANGER, "立即停止动作、当前任务和自主规划", b -> partner.command("stop", ""));
        autoButton = button("自主生存", left + 14 + (buttonWidth + gap) * 2, row, buttonWidth,
                Tone.NORMAL, "释放本地任务并恢复自主生存", b -> {
                    if (partner.command("release", "") == 1 && partner.command("auto", "on") == 1) onClose();
                });
        releaseButton = button("释放控制", left + 14 + (buttonWidth + gap) * 3, row,
                innerWidth - (buttonWidth + gap) * 3, Tone.NORMAL,
                "释放本地任务，让伙伴继续处理其他玩家的消息", b -> partner.command("release", ""));
        refreshAvailability();
    }

    private void initSettings() {
        endpoint = edit("模型地址", left + 14, bodyTop + 12, innerWidth, 2048, endpointDraft);
        endpoint.setResponder(value -> endpointDraft = value);
        model = edit("模型名称", left + 14, bodyTop + 46, innerWidth, 256, modelDraft);
        model.setResponder(value -> modelDraft = value);
        keyEnv = edit("API key 环境变量名", left + 14, bodyTop + 80, innerWidth, 256, keyDraft);
        keyEnv.setHint(Component.literal("本地模型留空；API 填环境变量名"));
        keyEnv.setResponder(value -> keyDraft = value);
        button("保存连接", left + 14, bodyTop + 106, 100, Tone.PRIMARY,
                "保存后请回任务页重新开启 AI", b -> saveSettings());
        button("重载配置", left + 120, bodyTop + 106, 94, Tone.NORMAL,
                "从当前游戏实例的配置文件重新读取设置", b -> {
                    if (partner.command("reload", "") == 1) {
                        endpointDraft = partner.config().baseUrl;
                        modelDraft = partner.config().model;
                        keyDraft = partner.config().apiKeyEnv;
                        settingsMessage = "配置已重载。";
                        settingsError = false;
                        rebuildWidgets();
                    }
                });
    }

    private EditBox edit(String label, int x, int y, int width, int length, String value) {
        EditBox box = addRenderableWidget(new EditBox(font, x, y, width, 20, Component.literal(label)));
        box.setMaxLength(length);
        box.setValue(value == null ? "" : value);
        box.setTextColor(TEXT);
        return box;
    }

    private Button button(String label, int x, int y, int width, Tone tone, String tooltip, Button.OnPress press) {
        Button button = addRenderableWidget(new PanelButton(x, y, width, 20, Component.literal(label), tone, press));
        button.setTooltip(Tooltip.create(Component.literal(tooltip)));
        return button;
    }

    private void switchTab(boolean value) {
        settings = value;
        rebuildWidgets();
    }

    private void submitGoal() {
        if (goal == null || goal.getValue().isBlank() || !partner.enabled()) return;
        if (partner.command("goal", goal.getValue().strip()) == 1) onClose();
    }

    private void saveSettings() {
        String address = endpoint.getValue().strip();
        String name = model.getValue().strip();
        String environment = keyEnv.getValue().strip();
        try {
            URI uri = URI.create(address);
            if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null)
                throw new IllegalArgumentException("模型地址应为完整的 http:// 或 https:// 地址。");
            if (name.isBlank()) throw new IllegalArgumentException("请填写模型名称。");
            if (!environment.isEmpty() && !environment.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new IllegalArgumentException("这里填写环境变量名，例如 MC_AI_API_KEY。");
            if (partner.command("endpoint", address) != 1 || partner.command("model", name) != 1
                    || partner.command("keyenv", environment) != 1)
                throw new IllegalStateException("连接保存失败，请查看本地聊天中的详细错误。");
            settingsMessage = "已保存。回任务页开启 AI 后使用新连接。";
            settingsError = false;
        } catch (Exception error) {
            settingsMessage = error.getMessage() == null ? "连接设置无效。" : error.getMessage();
            settingsError = true;
        }
    }

    private void refreshAvailability() {
        if (enableButton == null) return;
        enableButton.setMessage(Component.literal(partner.enabled() ? "关闭 AI" : "开启 AI"));
        sendButton.active = partner.enabled() && goal != null && !goal.getValue().isBlank();
        stopButton.active = autoButton.active = releaseButton.active = partner.enabled();
    }

    @Override public void tick() {
        super.tick();
        refreshAvailability();
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (!settings && goal != null && goal.isFocused()
                && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            submitGoal();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xBD07121B);
        graphics.fill(left + 3, top + 4, left + panelWidth + 3, top + panelHeight + 4, 0x66000000);
        graphics.fillGradient(left, top, left + panelWidth, top + panelHeight, 0xF51D2B37, 0xF513202B);
        graphics.fill(left, top, left + panelWidth, top + 2, ACCENT);
        graphics.text(font, "AI 生存伙伴", left + 14, top + 13, TEXT, false);
        String playerName = minecraft.player == null ? "未登录" : minecraft.player.getGameProfile().name();
        String subtitle = settings && !settingsMessage.isBlank() ? settingsMessage : "当前账号  " + playerName + "  ·  本地控制优先";
        graphics.text(font, fit(subtitle, innerWidth - 86), left + 14, top + 28, settings && settingsError ? ERROR : MUTED, false);
        int chipX = left + panelWidth - 80;
        graphics.fill(chipX, top + 13, left + panelWidth - 14, top + 31, partner.enabled() ? 0xFF254F47 : 0xFF304151);
        graphics.text(font, partner.enabled() ? "AI 已开启" : "AI 未开启", chipX + 7, top + 18, partner.enabled() ? ACCENT : MUTED, false);

        if (settings) renderSettings(graphics); else renderDashboard(graphics);
        graphics.fill(left + 14, footerTop - 5, left + panelWidth - 14, footerTop - 4, 0xFF324452);
        int hintWidth = panelWidth - 230;
        if (hintWidth > 70) graphics.text(font, fit("面板打开时暂停动作 · Esc 返回", hintWidth), left + 14, footerTop + 6, MUTED, false);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private void renderDashboard(GuiGraphicsExtractor graphics) {
        graphics.text(font, "给伙伴安排任务", left + 14, bodyTop + 2, TEXT, false);
        int cardTop = bodyTop + 76;
        int cardBottom = footerTop - 10;
        graphics.fill(left + 14, cardTop, left + panelWidth - 14, cardBottom, 0xFF101C26);
        int textX = left + 24;
        int textWidth = innerWidth - 20;
        graphics.text(font, "实时状态", textX, cardTop + 7, MUTED, false);
        int cursor = wrapped(graphics, partner.status(), textX, cardTop + 20, textWidth,
                cardBottom - cardTop > 80 ? 2 : 1, TEXT);
        if (cardBottom - cursor > 36) {
            graphics.text(font, fit("模型  " + partner.config().model, textWidth), textX, cursor + 6, ACCENT, false);
            cursor += 21;
        }
        String error = partner.lastError();
        String message = error.isBlank() ? partner.lastSpeech() : "模型错误：" + error;
        if (!message.isBlank() && cardBottom - cursor >= 13)
            wrapped(graphics, message, textX, cursor + 3, textWidth, Math.max(1, (cardBottom - cursor - 6) / 11), error.isBlank() ? MUTED : ERROR);
    }

    private void renderSettings(GuiGraphicsExtractor graphics) {
        graphics.text(font, "模型地址", left + 14, bodyTop + 1, TEXT, false);
        graphics.text(font, "模型名称", left + 14, bodyTop + 35, TEXT, false);
        graphics.text(font, "API key 环境变量名（本地模型可留空）", left + 14, bodyTop + 69, MUTED, false);
        int messageY = bodyTop + 132;
        if (footerTop - messageY > 12) {
            String message = settingsMessage.isBlank() ? "地址支持本地服务和兼容 API。API 密钥保存在环境变量中。" : settingsMessage;
            wrapped(graphics, message, left + 14, messageY, innerWidth, Math.max(1, (footerTop - messageY - 6) / 11), settingsError ? ERROR : MUTED);
        }
    }

    private String fit(String text, int availableWidth) {
        if (font.width(text) <= availableWidth) return text;
        return font.plainSubstrByWidth(text, Math.max(0, availableWidth - font.width("…"))) + "…";
    }

    private int wrapped(GuiGraphicsExtractor graphics, String text, int x, int y, int width, int maxLines, int color) {
        var lines = font.split(Component.literal(text), Math.max(1, width));
        int count = Math.min(maxLines, lines.size());
        for (int i = 0; i < count; i++) graphics.text(font, lines.get(i), x, y + i * 11, color, false);
        return y + count * 11;
    }

    @Override public boolean isPauseScreen() { return false; }

    private enum Tone { NORMAL, PRIMARY, DANGER, SELECTED }

    /** Native button behavior and narration, with a calm, readable panel palette. */
    private static final class PanelButton extends Button {
        private final Tone tone;

        private PanelButton(int x, int y, int width, int height, Component label, Tone tone, OnPress press) {
            super(x, y, width, height, label, press, DEFAULT_NARRATION);
            this.tone = tone;
        }

        @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            int background = switch (tone) {
                case PRIMARY -> isHoveredOrFocused() ? 0xFF398675 : 0xFF2A685B;
                case DANGER -> isHoveredOrFocused() ? 0xFF784244 : 0xFF4A3037;
                case SELECTED -> isHoveredOrFocused() ? 0xFF37596B : 0xFF2C4858;
                case NORMAL -> isHoveredOrFocused() ? 0xFF3B5062 : 0xFF283A49;
            };
            if (!active) background = 0xFF20303C;
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), background);
            if (isHoveredOrFocused() && active) {
                int border = tone == Tone.DANGER ? ERROR : ACCENT;
                graphics.fill(getX(), getY(), getX() + getWidth(), getY() + 1, border);
                graphics.fill(getX(), getY() + getHeight() - 1, getX() + getWidth(), getY() + getHeight(), border);
            }
            var font = Minecraft.getInstance().font;
            String label = getMessage().getString();
            if (font.width(label) > getWidth() - 8) label = font.plainSubstrByWidth(label, Math.max(1, getWidth() - 14)) + "…";
            graphics.text(font, label, getX() + (getWidth() - font.width(label)) / 2,
                    getY() + (getHeight() - 8) / 2, active ? TEXT : 0xFF738694, false);
        }
    }
}
