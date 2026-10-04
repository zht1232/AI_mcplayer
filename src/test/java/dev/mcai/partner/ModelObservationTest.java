package dev.mcai.partner;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelObservationTest {
    @Test void readableMenuKeepsIdentityActionAndRevisionWithoutGradientMarkup() {
        JsonObject raw = JsonParser.parseString("""
            {"ui":{"revision":11,"container":{"open":true,"slots":[
            {"slot":0,"item":"minecraft:paper","count":1,"name":{"text":"接受传送","component":{"text":"接受传送","color":"#ffffff"}},"lore":[{"text":"点击确认","component":{"text":"点击确认"}}],"tooltip":["duplicate"]},
            {"slot":1,"item":"minecraft:air","count":0,"playerInventory":false}]},
            "chat":[{"segments":[{"text":"接受","clickId":12,"clickAllowed":true,"style":{"color":"red","click_event":{"action":"run_command","command":"/tpaccept"}}},
            {"text":"请求","clickId":13,"clickAllowed":true,"style":{"color":"green","click_event":{"action":"run_command","command":"/tpaccept"}}}]}]}}
            """).getAsJsonObject();
        JsonObject compact = ModelObservation.compact(raw);
        JsonObject ui = compact.getAsJsonObject("ui");
        assertEquals(11, ui.get("revision").getAsInt());
        assertEquals("接受传送", ui.getAsJsonObject("container").getAsJsonArray("slots").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(2, ui.getAsJsonObject("container").getAsJsonArray("slots").size());
        assertEquals(1, ui.getAsJsonArray("chatActions").size());
        assertEquals(12, ui.getAsJsonArray("chatActions").get(0).getAsJsonObject().get("id").getAsInt());
        assertTrue(compact.toString().contains("/tpaccept"));
        assertFalse(compact.toString().contains("color"));
        assertTrue(raw.toString().contains("color"), "full diagnostic data must remain intact");
    }

    @Test void largeFormattedHudShrinksBeforeItReachesThePlanner() {
        JsonObject component = new JsonObject(); component.addProperty("text", "服务器状态正常");
        JsonArray rich = new JsonArray();
        for (int i = 0; i < 1000; i++) { JsonObject character = new JsonObject(); character.addProperty("text", "字"); character.addProperty("color", "#aabbcc"); rich.add(character); }
        component.add("component", rich);
        JsonObject tab = new JsonObject(); tab.add("header", component);
        JsonObject hud = new JsonObject(); hud.add("tab", tab);
        JsonObject ui = new JsonObject(); ui.add("hud", hud); ui.add("chat", new JsonArray());
        JsonObject raw = new JsonObject(); raw.add("ui", ui);
        assertTrue(ModelObservation.compact(raw).toString().length() < raw.toString().length() / 20);
    }

    @Test void sceneDataIsSelectedByTaskAndCanBeRequestedOnDemand() {
        JsonObject raw = JsonParser.parseString("""
            {"selfAndWorld":{"x":1,"visibleBlocks":[{"id":"minecraft:oak_log"}],"safeDestinations":[{"x":2}]},
            "ui":{"screen":"world","container":{"open":false,"slots":[]},"chat":[],"hud":{"bossBars":[]}}}
            """).getAsJsonObject();
        JsonObject chat = ModelObservation.select(raw, "玩家 Steve 对你说：你好\n若要求干活执行", java.util.Set.of(), null);
        assertEquals("conversation", chat.get("observationMode").getAsString());
        assertFalse(chat.getAsJsonObject("selfAndWorld").has("visibleBlocks"));
        assertFalse(chat.getAsJsonObject("ui").has("container"));
        JsonObject requested = ModelObservation.select(raw, "你好", java.util.Set.of("world", "inventory"), null);
        assertTrue(requested.getAsJsonObject("selfAndWorld").has("safeDestinations"));
        assertTrue(requested.getAsJsonObject("ui").has("container"));
    }
}
