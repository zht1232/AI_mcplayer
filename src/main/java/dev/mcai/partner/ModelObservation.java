package dev.mcai.partner;

import com.google.gson.*;
import java.util.*;

/** Readable, bounded planner view. Full client observations remain available for diagnostics. */
public final class ModelObservation {
    private static final int MAX_CHARACTERS = 16000;
    private ModelObservation() {}

    public static JsonObject compact(JsonObject source) {
        JsonObject result = readable(source).getAsJsonObject();
        JsonObject ui = result.has("ui") ? result.getAsJsonObject("ui") : new JsonObject();
        JsonObject rawUi = source.has("ui") ? source.getAsJsonObject("ui") : new JsonObject();
        ui.remove("limitations");
        if (rawUi.has("dialog")) ui.addProperty("dialogText", limit(extractText(rawUi.get("dialog")), 1200));
        ui.remove("dialog"); // Live widget handlers stay in the client; large item components are not prompts.
        if (ui.has("container")) {
            JsonObject container = ui.getAsJsonObject("container");
            boolean open = container.has("open") && container.get("open").getAsBoolean();
            JsonArray items = new JsonArray();
            for (JsonElement value : container.getAsJsonArray("slots")) {
                JsonObject item = value.getAsJsonObject();
                item.remove("tooltip"); item.remove("containerSlot");
                boolean empty = !item.has("count") || item.get("count").getAsInt() == 0;
                if (empty && !open) continue;
                if (empty) {
                    JsonObject target = new JsonObject(); target.add("slot", item.get("slot"));
                    if (item.has("playerInventory")) target.add("playerInventory", item.get("playerInventory"));
                    items.add(target);
                } else {
                    if (item.has("lore")) item.add("lore", headAndTail(item.getAsJsonArray("lore"), 2));
                    items.add(item);
                }
            }
            container.add("slots", items);
        }
        compactChat(rawUi, ui);
        if (ui.has("hud")) {
            JsonObject hud = ui.getAsJsonObject("hud");
            if (hud.has("tab")) cap(hud.getAsJsonObject("tab"), "players", 16);
            if (hud.has("scoreboards")) {
                Set<String> seen = new HashSet<>(); JsonArray boards = new JsonArray();
                for (JsonElement value : hud.getAsJsonArray("scoreboards")) {
                    JsonObject board = value.getAsJsonObject(); cap(board, "lines", 12);
                    String signature = board.get("title") + ":" + board.get("lines");
                    if (seen.add(signature)) boards.add(board);
                    if (boards.size() == 3) break;
                }
                hud.add("scoreboards", boards);
            }
        }
        if (result.has("selfAndWorld")) {
            JsonObject world = result.getAsJsonObject("selfAndWorld");
            cap(world, "visibleBlocks", 24); cap(world, "visibleEntities", 16);
        }
        result.add("ui", ui);
        enforceBudget(result);
        return result;
    }

    public static JsonObject select(JsonObject source, String goal, Set<String> requested, Integer inspectedSlot) {
        JsonObject result = compact(source);
        JsonObject ui = result.getAsJsonObject("ui");
        JsonObject rawUi = source.getAsJsonObject("ui");
        String content = goal == null ? "" : goal;
        int author = content.indexOf("对你说：");
        if (author >= 0) content = content.substring(author + 4).split("\\n", 2)[0];
        boolean menuOpen = rawUi.has("dialog") || (rawUi.has("container") && rawUi.getAsJsonObject("container").get("open").getAsBoolean());
        boolean working = content.matches("(?is).*(自主|采|挖|走|移动|跟|过来|收集|种|建|造|放置|战斗|攻击|整理|存|取|拿|给|吃|交易|买|卖|传送|tp|钓|合成|熔|返回|打开|关闭|周围|环境|看见).*");
        boolean inventory = menuOpen || working || content.matches("(?is).*(背包|物品|装备|食物|材料|木头|资源).*") || requested.contains("inventory") || requested.contains("menu");
        boolean world = !menuOpen && (working || requested.contains("world"));
        boolean hud = requested.contains("hud") || content.matches("(?is).*(状态|进度|效果|天气|时间|血量|提示|tpa|tpaccept|金币|余额).*");
        if (source.has("notes")) result.add("notes", source.get("notes").deepCopy());
        if (!world && result.has("selfAndWorld")) {
            JsonObject surroundings = result.getAsJsonObject("selfAndWorld");
            surroundings.remove("visibleBlocks"); surroundings.remove("safeDestinations");
            cap(surroundings, "visibleEntities", 4);
        }
        if (!inventory) ui.remove("container");
        if (!hud) {
            JsonObject status = ui.has("hud") ? ui.getAsJsonObject("hud") : new JsonObject();
            JsonObject brief = new JsonObject();
            if (status.has("actionBar")) brief.add("actionBar", status.get("actionBar"));
            ui.add("hud", brief);
        }
        if (!menuOpen) ui.remove("widgets");
        if (inspectedSlot != null && rawUi.has("container")) {
            for (JsonElement value : rawUi.getAsJsonObject("container").getAsJsonArray("slots")) {
                JsonObject slot = value.getAsJsonObject();
                if (slot.get("slot").getAsInt() == inspectedSlot) {
                    JsonObject detail = readable(slot).getAsJsonObject();
                    if (detail.has("lore")) cap(detail, "lore", 10);
                    ui.add("slotDetails", detail);
                    break;
                }
            }
        }
        result.addProperty("observationMode", menuOpen ? "menu" : world ? "world" : "conversation");
        result.addProperty("observationNote", "This is a task-selected view. Use observe(world/inventory/hud/chat/menu) or inspect_slot when more detail is needed. Absence from this view is not proof of absence from the world.");
        return result;
    }

    private static JsonElement readable(JsonElement value) {
        if (value == null || value.isJsonNull()) return JsonNull.INSTANCE;
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            return primitive.isString() ? new JsonPrimitive(limit(primitive.getAsString(), 320)) : primitive.deepCopy();
        }
        if (value.isJsonArray()) {
            JsonArray array = new JsonArray(); for (JsonElement child : value.getAsJsonArray()) array.add(readable(child)); return array;
        }
        JsonObject object = value.getAsJsonObject();
        if (object.has("text") && object.has("component")) return new JsonPrimitive(limit(object.get("text").getAsString(), 320));
        JsonObject copy = new JsonObject();
        for (var entry : object.entrySet()) {
            if (entry.getKey().equals("tooltip") || entry.getKey().equals("receivedAt")) continue;
            copy.add(entry.getKey(), readable(entry.getValue()));
        }
        return copy;
    }

    private static void compactChat(JsonObject raw, JsonObject ui) {
        JsonArray messages = new JsonArray(); JsonArray actions = new JsonArray();
        JsonArray chat = raw.has("chat") ? raw.getAsJsonArray("chat") : new JsonArray();
        Map<String, JsonObject> buttons = new LinkedHashMap<>();
        for (int i = Math.max(0, chat.size() - 6); i < chat.size(); i++) {
            JsonObject message = chat.get(i).getAsJsonObject(); StringBuilder text = new StringBuilder();
            for (JsonElement value : message.getAsJsonArray("segments")) {
                JsonObject segment = value.getAsJsonObject(); String part = segment.get("text").getAsString(); text.append(part);
                if (!segment.has("clickId")) continue;
                JsonObject style = segment.has("style") && segment.get("style").isJsonObject() ? segment.getAsJsonObject("style") : new JsonObject();
                JsonElement event = style.has("click_event") ? style.get("click_event") : style.get("clickEvent");
                String key = i + ":" + (event == null ? segment.get("clickId").toString() : event.toString());
                JsonObject button = buttons.get(key);
                if (button == null) {
                    button = new JsonObject(); button.add("id", segment.get("clickId")); button.addProperty("text", "");
                    if (segment.has("clickAllowed")) button.add("allowed", segment.get("clickAllowed"));
                    if (event != null && event.isJsonObject()) {
                        JsonObject summary = new JsonObject();
                        for (String field : List.of("action", "command", "id", "url")) if (event.getAsJsonObject().has(field)) summary.add(field, readable(event.getAsJsonObject().get(field)));
                        button.add("action", summary);
                    }
                    JsonElement hover = style.has("hover_event") ? style.get("hover_event") : style.get("hoverEvent");
                    if (hover != null) button.addProperty("hover", limit(extractText(hover), 320));
                    buttons.put(key, button);
                }
                button.addProperty("text", limit(button.get("text").getAsString() + part, 320));
            }
            JsonObject simplified = new JsonObject(); simplified.addProperty("text", limit(text.toString(), 640)); messages.add(simplified);
        }
        for (JsonObject button : buttons.values()) { actions.add(button); if (actions.size() >= 24) break; }
        ui.add("chat", messages); ui.add("chatActions", actions);
    }

    private static String extractText(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) return value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
        StringBuilder text = new StringBuilder();
        if (value.isJsonArray()) for (JsonElement child : value.getAsJsonArray()) text.append(extractText(child));
        else for (var entry : value.getAsJsonObject().entrySet()) if (!Set.of("color", "font", "id", "action").contains(entry.getKey())) text.append(extractText(entry.getValue()));
        return text.toString();
    }

    private static void enforceBudget(JsonObject result) {
        if (result.toString().length() <= MAX_CHARACTERS) return;
        JsonObject ui = result.getAsJsonObject("ui");
        ui.addProperty("plannerViewLimited", true);
        if (ui.has("hud")) {
            JsonObject hud = ui.getAsJsonObject("hud");
            if (hud.has("tab")) { JsonObject tab = hud.getAsJsonObject("tab"); cap(tab, "players", 8); tab.remove("header"); tab.remove("footer"); }
            cap(hud, "scoreboards", 1);
        }
        if (result.toString().length() > MAX_CHARACTERS && ui.has("container")) {
            for (JsonElement value : ui.getAsJsonObject("container").getAsJsonArray("slots")) {
                JsonObject slot = value.getAsJsonObject();
                if (slot.has("lore")) slot.add("lore", headAndTail(slot.getAsJsonArray("lore"), 2));
                slot.remove("customModelData");
            }
        }
        if (result.toString().length() > MAX_CHARACTERS && ui.has("container")) {
            JsonArray minimal = new JsonArray();
            for (JsonElement value : ui.getAsJsonObject("container").getAsJsonArray("slots")) {
                JsonObject slot = value.getAsJsonObject(); JsonObject item = new JsonObject();
                for (String field : List.of("slot", "item", "count", "playerInventory")) if (slot.has(field)) item.add(field, slot.get(field));
                if (slot.has("name") && slot.get("name").isJsonPrimitive()) item.addProperty("name", limit(slot.get("name").getAsString(), 64));
                if (slot.has("lore") && !slot.getAsJsonArray("lore").isEmpty()) {
                    JsonArray hint = new JsonArray(); hint.add(limit(slot.getAsJsonArray("lore").get(slot.getAsJsonArray("lore").size() - 1).getAsString(), 80)); item.add("lore", hint);
                }
                minimal.add(item);
            }
            ui.getAsJsonObject("container").add("slots", minimal);
        }
        if (result.toString().length() > MAX_CHARACTERS) {
            if (result.has("selfAndWorld")) { cap(result.getAsJsonObject("selfAndWorld"), "visibleBlocks", 8); cap(result.getAsJsonObject("selfAndWorld"), "visibleEntities", 8); }
            cap(ui, "chat", 3); cap(ui, "chatActions", 12);
        }
    }

    private static String limit(String value, int max) { return value.length() <= max ? value : value.substring(0, max) + "…"; }
    private static void cap(JsonObject parent, String key, int count) { if (parent.has(key) && parent.get(key).isJsonArray()) parent.add(key, headAndTail(parent.getAsJsonArray(key), count)); }
    private static JsonArray headAndTail(JsonArray original, int count) {
        JsonArray result = new JsonArray();
        if (original.size() <= count) return original;
        for (int i = 0; i < count - 1; i++) result.add(original.get(i));
        result.add(original.get(original.size() - 1)); return result;
    }
}
