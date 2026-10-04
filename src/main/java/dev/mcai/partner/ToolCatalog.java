package dev.mcai.partner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

public final class ToolCatalog {
    private ToolCatalog() {}
    private static JsonObject tool(String name, String description, String... fields) {
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (String field : fields) {
            String[] parts = field.split(":", 2);
            JsonObject type = new JsonObject(); type.addProperty("type", parts[1]);
            properties.add(parts[0], type); required.add(parts[0]);
        }
        JsonObject args = new JsonObject(); args.addProperty("type", "object");
        args.add("properties", properties); args.add("required", required); args.addProperty("additionalProperties", false);
        JsonObject function = new JsonObject(); function.addProperty("name", name);
        function.addProperty("description", description); function.add("parameters", args);
        JsonObject result = new JsonObject(); result.addProperty("type", "function"); result.add("function", function); return result;
    }
    public static List<JsonObject> schemas() {
        List<JsonObject> tools = new ArrayList<>();
        tools.add(tool("move_to", "Walk through known loaded terrain to a nearby position. No terrain editing. Wait for arrival before another movement task.", "x:integer", "y:integer", "z:integer"));
        tools.add(tool("follow_player", "Follow a currently loaded player until the task is stopped.", "name:string"));
        tools.add(tool("collect_nearby", "Walk to the nearest visible dropped item; verify actual pickup in inventory."));
        tools.add(tool("open_inventory", "Open own inventory to move existing items and craft using its 2x2 grid."));
        tools.add(tool("dig_block", "Mine an observed reachable block using equipped tool; wait for the actual block change.", "x:integer", "y:integer", "z:integer"));
        tools.add(tool("place_block", "Place held block against an observed reachable supporting block face; face is up/down/north/south/east/west.", "x:integer", "y:integer", "z:integer", "face:string"));
        tools.add(tool("select_hotbar", "Select an existing hotbar item, index 0–8.", "slot:integer"));
        tools.add(tool("eat", "Eat food already in hotbar using normal client interaction."));
        tools.add(tool("use_block", "Right-click an observed reachable block to open its container or interact.", "x:integer", "y:integer", "z:integer"));
        tools.add(tool("attack_nearest", "Attack the nearest visible living hostile mob in melee reach."));
        tools.add(tool("ui_click_slot", "Click a current container slot. button: left/right/shift_left/shift_right. Copy ui revision from current observation.", "slot:integer", "button:string", "revision:integer"));
        tools.add(tool("ui_click_chat", "Activate an observed chat action. Copy action id from ui.chatActions.", "id:integer"));
        tools.add(tool("ui_input_chat", "Send a chat input or complete the command suggested by a shop, using the normal player connection.", "text:string"));
        tools.add(tool("ui_widget", "Activate an observed Dialog/screen widget or fill an input; value is empty for a button. Copy current revision.", "index:integer", "value:string", "revision:integer"));
        tools.add(tool("ui_close", "Close current in-game UI normally."));
        tools.add(tool("chat_say", "Say a brief message to players; avoid repeating messages.", "text:string"));
        tools.add(tool("wait", "Observe server feedback while no physical task runs; seconds 1–30.", "seconds:integer"));
        tools.add(tool("complete_goal", "Finish an explicit goal only after its postcondition is visible. Explain the evidence.", "evidence:string"));
        tools.add(tool("remember", "Persist a short useful fact about the current server, capped to avoid unlimited memory.", "text:string"));
        tools.add(tool("observe", "Request additional data for the next decision: world, inventory, hud, chat, or menu. Use only when the current task-selected view lacks needed information.", "area:string"));
        tools.add(tool("inspect_slot", "Request detailed name, lore and item data for one currently observed container slot.", "slot:integer"));
        return tools;
    }
}
