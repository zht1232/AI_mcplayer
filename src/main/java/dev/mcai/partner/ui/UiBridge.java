package dev.mcai.partner.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import dev.mcai.partner.mixin.ui.BossOverlayAccessor;
import dev.mcai.partner.mixin.ui.DialogScreenAccessor;
import dev.mcai.partner.mixin.ui.HudAccessor;
import dev.mcai.partner.mixin.ui.SliderAccessor;
import dev.mcai.partner.mixin.ui.TabOverlayAccessor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.Style;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.network.chat.numbers.StyledFormat;

/** All methods run on the Minecraft client thread. Server text is observation data. */
public final class UiBridge {
    private final Minecraft client;
    private final ArrayDeque<ChatEntry> chat = new ArrayDeque<>();
    private final Map<Integer, ClickEvent> chatActions = new HashMap<>();
    private int nextChatId = 1;
    private long revision;
    private Screen lastScreen;
    private AbstractContainerMenu lastMenu;
    private int lastState = -1;
    private List<ItemStack> lastItems = List.of();
    private ItemStack lastCursor = ItemStack.EMPTY;
    private Screen widgetScreen;
    private List<AbstractWidget> observedWidgets = List.of();

    public UiBridge(Minecraft client) { this.client = client; }

    public void tick() { refreshRevision(); }

    public void clear() {
        chat.clear();
        chatActions.clear();
        lastScreen = null;
        lastMenu = null;
        lastItems = List.of();
        lastCursor = ItemStack.EMPTY;
        lastState = -1;
        widgetScreen = null;
        observedWidgets = List.of();
        revision++;
    }

    private AbstractContainerMenu menu() {
        if (client.player == null) return null;
        return client.gui.screen() instanceof AbstractContainerScreen<?> screen
                ? screen.getMenu() : client.player.containerMenu;
    }

    private void refreshRevision() {
        Screen screen = client.gui.screen();
        AbstractContainerMenu menu = menu();
        boolean changed = screen != lastScreen || menu != lastMenu;
        if (menu != null) {
            changed |= menu.getStateId() != lastState || menu.slots.size() != lastItems.size()
                    || !ItemStack.matches(menu.getCarried(), lastCursor);
            if (!changed) for (int i = 0; i < menu.slots.size(); i++) {
                if (!ItemStack.matches(menu.slots.get(i).getItem(), lastItems.get(i))) { changed = true; break; }
            }
        }
        if (changed) {
            revision++;
            lastScreen = screen;
            lastMenu = menu;
            lastState = menu == null ? -1 : menu.getStateId();
            lastItems = menu == null ? List.of() : menu.slots.stream().map(s -> s.getItem().copy()).toList();
            lastCursor = menu == null ? ItemStack.EMPTY : menu.getCarried().copy();
        }
    }

    public JsonObject snapshot() {
        refreshRevision();
        JsonObject result = new JsonObject();
        result.addProperty("revision", revision);
        Screen screen = client.gui.screen();
        result.addProperty("screen", screen == null ? "world" : screen.getClass().getSimpleName());
        result.add("title", component(screen == null ? null : screen.getTitle()));
        AbstractContainerMenu menu = menu();
        if (menu != null) {
            JsonObject container = new JsonObject();
            container.addProperty("windowId", menu.containerId);
            container.addProperty("stateId", menu.getStateId());
            container.addProperty("revision", revision);
            container.addProperty("open", screen instanceof AbstractContainerScreen<?>);
            try { container.addProperty("type", BuiltInRegistries.MENU.getKey(menu.getType()).toString()); }
            catch (UnsupportedOperationException e) { container.addProperty("type", "minecraft:player_inventory"); }
            JsonArray slots = new JsonArray();
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                JsonObject entry = item(slot.getItem());
                entry.addProperty("slot", i);
                entry.addProperty("containerSlot", slot.getContainerSlot());
                entry.addProperty("playerInventory", slot.container == client.player.getInventory());
                entry.addProperty("active", slot.isActive());
                slots.add(entry);
            }
            container.add("slots", slots);
            container.add("cursor", item(menu.getCarried()));
            result.add("container", container);
        }
        observedWidgets = collectWidgets(screen);
        widgetScreen = screen;
        JsonArray widgets = new JsonArray();
        for (int i = 0; i < observedWidgets.size(); i++) {
            AbstractWidget widget = observedWidgets.get(i);
            JsonObject entry = new JsonObject();
            entry.addProperty("index", i);
            entry.addProperty("type", widget.getClass().getSimpleName());
            entry.add("label", component(widget.getMessage()));
            entry.addProperty("active", widget.active);
            entry.addProperty("visible", widget.visible);
            entry.addProperty("x", widget.getX());
            entry.addProperty("y", widget.getY());
            entry.addProperty("width", widget.getWidth());
            entry.addProperty("height", widget.getHeight());
            if (widget instanceof EditBox box) entry.addProperty("value", box.getValue());
            if (widget instanceof MultiLineEditBox box) entry.addProperty("value", box.getValue());
            if (widget instanceof Checkbox box) entry.addProperty("selected", box.selected());
            if (widget instanceof CycleButton<?> box) entry.addProperty("value", String.valueOf(box.getValue()));
            if (widget instanceof AbstractSliderButton slider) entry.addProperty("normalizedValue", ((SliderAccessor)slider).mcai$value());
            widgets.add(entry);
        }
        result.add("widgets", widgets);
        if (screen instanceof DialogScreen<?> dialog) result.add("dialog", encode(Dialog.DIRECT_CODEC, ((DialogScreenAccessor)dialog).mcai$dialog()));
        JsonArray messages = new JsonArray();
        JsonArray actions = new JsonArray();
        for (ChatEntry entry : chat) {
            messages.add(entry.json.deepCopy());
            for (JsonElement element : entry.json.getAsJsonArray("segments")) {
                JsonObject segment = element.getAsJsonObject();
                if (!segment.has("clickId")) continue;
                JsonObject action = new JsonObject();
                action.add("id", segment.get("clickId"));
                action.add("text", segment.get("text"));
                action.add("allowed", segment.get("clickAllowed"));
                actions.add(action);
            }
        }
        result.add("chat", messages);
        result.add("chatActions", actions);
        result.add("hud", hud());
        JsonArray limitations = new JsonArray();
        limitations.add("Resource-pack image/glyph meaning is not inferred from textures; names, lore, component fonts and model data are retained.");
        limitations.add("Custom canvas-only screens without standard widgets need an adapter or visual perception.");
        result.add("limitations", limitations);
        return result;
    }

    private JsonObject item(ItemStack stack) {
        JsonObject result = new JsonObject();
        result.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        result.addProperty("count", stack.getCount());
        if (stack.isEmpty()) return result;
        result.add("name", component(stack.getHoverName()));
        JsonArray lore = new JsonArray();
        ItemLore itemLore = stack.get(DataComponents.LORE);
        if (itemLore != null) for (Component line : itemLore.lines()) lore.add(component(line));
        result.add("lore", lore);
        JsonArray tooltip = new JsonArray();
        try { for (Component line : Screen.getTooltipFromItem(client, stack)) tooltip.add(component(line)); }
        catch (RuntimeException e) { result.addProperty("tooltipError", e.getClass().getSimpleName()); }
        result.add("tooltip", tooltip);
        var model = stack.get(DataComponents.ITEM_MODEL);
        if (model != null) result.addProperty("itemModel", model.toString());
        var modelData = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        if (modelData != null) result.add("customModelData", encode(net.minecraft.world.item.component.CustomModelData.CODEC, modelData));
        return result;
    }

    public void onMessage(Component message, boolean overlay) {
        if (overlay || message == null) return; // ActionBar comes from the live HUD with expiration.
        JsonObject entry = new JsonObject();
        entry.add("message", component(message));
        entry.addProperty("receivedAt", System.currentTimeMillis());
        JsonArray segments = new JsonArray();
        List<Integer> actionIds = new ArrayList<>();
        message.visit((style, text) -> {
            JsonObject segment = new JsonObject();
            segment.addProperty("text", text);
            segment.add("style", encode(Style.Serializer.CODEC, style));
            if (style.getClickEvent() != null) {
                int id = nextChatId++;
                actionIds.add(id);
                chatActions.put(id, style.getClickEvent());
                segment.addProperty("clickId", id);
                segment.addProperty("clickAllowed", allowed(style.getClickEvent()));
                segment.add("click", encode(ClickEvent.CODEC, style.getClickEvent()));
            }
            if (style.getHoverEvent() != null) segment.add("hover", encode(net.minecraft.network.chat.HoverEvent.CODEC, style.getHoverEvent()));
            segments.add(segment);
            return Optional.empty();
        }, Style.EMPTY);
        entry.add("segments", segments);
        chat.addLast(new ChatEntry(entry, actionIds));
        while (chat.size() > 30) for (int id : chat.removeFirst().actionIds) chatActions.remove(id);
    }

    public String clickSlot(int slot, String button, long expectedRevision) {
        refreshRevision();
        if (client.player == null || client.gameMode == null) return "失败：未进入世界";
        if (expectedRevision != revision) return "失败：菜单已变化，请重新观察；当前 revision=" + revision;
        AbstractContainerMenu menu = menu();
        if (menu == null || slot < 0 || slot >= menu.slots.size() || !menu.slots.get(slot).isActive()) return "失败：槽位无效";
        if (client.gui.screen() != null && !(client.gui.screen() instanceof AbstractContainerScreen<?>)) return "失败：当前界面不是容器";
        ContainerInput input;
        int mouse;
        switch (button.toUpperCase(Locale.ROOT)) {
            case "LEFT" -> { input = ContainerInput.PICKUP; mouse = 0; }
            case "RIGHT" -> { input = ContainerInput.PICKUP; mouse = 1; }
            case "SHIFT_LEFT" -> { input = ContainerInput.QUICK_MOVE; mouse = 0; }
            case "SHIFT_RIGHT" -> { input = ContainerInput.QUICK_MOVE; mouse = 1; }
            default -> { return "失败：按钮仅支持 LEFT、RIGHT、SHIFT_LEFT、SHIFT_RIGHT"; }
        }
        client.gameMode.handleContainerInput(menu.containerId, slot, mouse, input, client.player);
        revision++; // Every issued action invalidates the old observation, including commands without item changes.
        return "已发送正常客户端槽位点击；等待服务器反馈";
    }

    private boolean allowed(ClickEvent event) {
        if (event instanceof ClickEvent.RunCommand command && localCommand(command.command())) return false;
        if (event instanceof ClickEvent.SuggestCommand command && localCommand(command.command())) return false;
        return event instanceof ClickEvent.RunCommand || event instanceof ClickEvent.SuggestCommand
                || event instanceof ClickEvent.ShowDialog || event instanceof ClickEvent.Custom;
    }

    public String clickChat(int id) {
        if (client.player == null) return "失败：未进入世界";
        ClickEvent event = chatActions.get(id);
        if (event == null) return "失败：聊天点击已过期或不存在";
        if (!allowed(event)) return "失败：只允许游戏内命令、命令建议、Dialog 和自定义点击；外部链接不可执行";
        Screen screen = client.gui.screen();
        if (event instanceof ClickEvent.SuggestCommand) {
            if (!(screen instanceof ChatScreen)) {
                screen = new ChatScreen("", false);
                client.gui.setScreen(screen);
            }
        }
        GameClickScreen.dispatch(event, client, screen);
        refreshRevision();
        return event instanceof ClickEvent.SuggestCommand ? "已将建议写入聊天输入框，尚未发送" : "已执行聊天点击；等待服务器反馈";
    }

    public String inputChat(String text) {
        if (client.player == null) return "失败：未进入世界";
        if (text == null || text.isBlank() || text.length() > 256 || text.contains("\n") || text.contains("\r")) return "失败：聊天输入须为 1–256 字符的单行文本";
        if (localCommand(text)) return "失败：AI 聊天工具不能调用 /aip 本地控制命令";
        ChatScreen screen = client.gui.screen() instanceof ChatScreen current ? current : new ChatScreen("", false);
        // Uses vanilla normalization, history and signed command/chat sending.
        screen.handleChatInput(text, true);
        if (client.gui.screen() == screen) screen.onClose();
        refreshRevision();
        return "已发送聊天输入；等待服务器反馈";
    }
    private boolean localCommand(String text) {
        String value = text.strip().toLowerCase(Locale.ROOT);
        return value.equals("/aip") || value.startsWith("/aip ") || value.equals("/wildling") || value.startsWith("/wildling ");
    }

    public String closeUi() {
        Screen screen = client.gui.screen();
        if (screen == null) return "当前无界面";
        screen.onClose();
        refreshRevision();
        return "已请求正常关闭界面";
    }

    public String screenAction(int index, String value) {
        Screen screen = client.gui.screen();
        if (screen == null || screen != widgetScreen) return "失败：界面已变化，请重新观察";
        List<AbstractWidget> now = collectWidgets(screen);
        if (index < 0 || index >= observedWidgets.size() || index >= now.size()
                || now.get(index) != observedWidgets.get(index)) return "失败：控件已变化或索引无效，请重新观察";
        AbstractWidget widget = now.get(index);
        if (!widget.active || !widget.visible) return "失败：控件当前不可用";
        if (widget instanceof EditBox box) {
            box.setValue(value == null ? "" : value);
            widget.setFocused(true);
            return "已填写输入框，尚未提交";
        }
        if (widget instanceof MultiLineEditBox box) {
            box.setValue(value == null ? "" : value);
            widget.setFocused(true);
            return "已填写多行输入框，尚未提交";
        }
        double x = widget.getX() + widget.getWidth() / 2.0;
        if (widget instanceof AbstractSliderButton && value != null && !value.isBlank()) {
            try {
                double position = Double.parseDouble(value);
                if (!Double.isFinite(position) || position < 0 || position > 1) return "失败：滑块 value 须为 0–1";
                x = widget.getX() + 4 + position * Math.max(0, widget.getWidth() - 8);
            } catch (NumberFormatException e) { return "失败：滑块 value 须为 0–1"; }
        }
        MouseButtonEvent click = new MouseButtonEvent(x, widget.getY() + widget.getHeight() / 2.0, new MouseButtonInfo(0, 0));
        boolean handled = widget.mouseClicked(click, false);
        if (handled) widget.mouseReleased(click);
        refreshRevision();
        return handled ? "已点击控件；等待界面或服务器反馈" : "该控件未接受点击，需要专用交互适配";
    }

    private List<AbstractWidget> collectWidgets(Screen screen) {
        if (screen == null) return List.of();
        List<AbstractWidget> widgets = new ArrayList<>();
        Set<GuiEventListener> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (GuiEventListener child : screen.children()) collect(child, widgets, seen);
        return widgets;
    }

    private void collect(GuiEventListener listener, List<AbstractWidget> widgets, Set<GuiEventListener> seen) {
        if (!seen.add(listener)) return;
        if (listener instanceof AbstractWidget widget) widgets.add(widget);
        if (listener instanceof ContainerEventHandler parent) for (GuiEventListener child : parent.children()) collect(child, widgets, seen);
    }

    private JsonObject hud() {
        JsonObject result = new JsonObject();
        var hud = client.gui.hud;
        HudAccessor access = (HudAccessor)hud;
        result.add("actionBar", component(access.mcai$actionBarTicks() > 0 ? access.mcai$actionBar() : null));
        result.add("title", component(access.mcai$titleTicks() > 0 ? access.mcai$title() : null));
        result.add("subtitle", component(access.mcai$titleTicks() > 0 ? access.mcai$subtitle() : null));
        JsonArray bars = new JsonArray();
        for (var event : ((BossOverlayAccessor)hud.getBossOverlay()).mcai$events().values()) {
            JsonObject bar = new JsonObject();
            bar.addProperty("id", event.getId().toString());
            bar.add("name", component(event.getName()));
            bar.addProperty("progress", event.getProgress());
            bar.addProperty("color", event.getColor().name());
            bar.addProperty("overlay", event.getOverlay().name());
            bars.add(bar);
        }
        result.add("bossBars", bars);
        var tab = hud.getTabList();
        TabOverlayAccessor tabAccess = (TabOverlayAccessor)tab;
        JsonObject tabData = new JsonObject();
        tabData.add("header", component(tabAccess.mcai$header()));
        tabData.add("footer", component(tabAccess.mcai$footer()));
        JsonArray players = new JsonArray();
        if (client.getConnection() != null) client.getConnection().getListedOnlinePlayers().stream()
                .sorted(java.util.Comparator.comparingInt(net.minecraft.client.multiplayer.PlayerInfo::getTabListOrder).reversed()
                        .thenComparing(p -> p.getProfile().name())).limit(80).forEach(info -> {
                    JsonObject player = new JsonObject();
                    player.addProperty("name", info.getProfile().name());
                    player.add("display", component(tab.getNameForDisplay(info)));
                    player.addProperty("ping", info.getLatency());
                    players.add(player);
                });
        tabData.add("players", players);
        result.add("tab", tabData);
        JsonArray objectives = new JsonArray();
        if (client.getConnection() != null) {
            Scoreboard board = client.getConnection().scoreboard();
            for (DisplaySlot display : DisplaySlot.values()) {
                Objective objective = board.getDisplayObjective(display);
                if (objective == null) continue;
                JsonObject data = new JsonObject();
                data.addProperty("displaySlot", display.getSerializedName());
                data.add("title", component(objective.getDisplayName()));
                JsonArray lines = new JsonArray();
                board.listPlayerScores(objective).stream().filter(s -> !s.isHidden())
                        .sorted(java.util.Comparator.comparingInt(PlayerScoreEntry::value).reversed().thenComparing(PlayerScoreEntry::owner))
                        .limit(30).forEach(score -> {
                            JsonObject line = new JsonObject();
                            line.add("text", component(PlayerTeam.formatNameForTeam(board.getPlayersTeam(score.owner()), score.ownerName())));
                            line.addProperty("score", score.value());
                            line.add("formattedScore", component(score.formatValue(objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT))));
                            lines.add(line);
                        });
                data.add("lines", lines);
                objectives.add(data);
            }
        }
        result.add("scoreboards", objectives);
        return result;
    }

    private JsonElement component(Component component) {
        if (component == null) return JsonNull.INSTANCE;
        JsonObject result = new JsonObject();
        result.addProperty("text", component.getString(4096));
        result.add("component", encode(ComponentSerialization.CODEC, component));
        return result;
    }

    private <T> JsonElement encode(Codec<T> codec, T value) {
        try {
            DynamicOps<JsonElement> ops = client.getConnection() == null ? JsonOps.INSTANCE
                    : client.getConnection().registryAccess().createSerializationContext(JsonOps.INSTANCE);
            return codec.encodeStart(ops, value).result().orElseGet(() -> new JsonPrimitive(String.valueOf(value)));
        } catch (RuntimeException e) { return new JsonPrimitive(String.valueOf(value)); }
    }

    private record ChatEntry(JsonObject json, List<Integer> actionIds) {}

    /** Subclass exposes vanilla protected click dispatch without duplicating its networking behavior. */
    private static final class GameClickScreen extends Screen {
        private GameClickScreen() { super(Component.empty()); }
        static void dispatch(ClickEvent event, Minecraft client, Screen current) {
            defaultHandleGameClickEvent(event, client, current);
        }
    }
}
