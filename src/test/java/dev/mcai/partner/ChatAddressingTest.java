package dev.mcai.partner;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatAddressingTest {
    @Test void ordinaryNameAndAtMentionsAddressTheLoggedInAccount() {
        var direct = ChatAddressing.signed("@AI_Partner 今天做什么？", "Steve", "AI_Partner", "!ai ").orElseThrow();
        assertEquals("Steve", direct.speaker());
        assertEquals("今天做什么？", direct.text());
        assertEquals("你好", ChatAddressing.signed("ai_partner，你好", "Alex", "AI_Partner", "!ai ").orElseThrow().text());
        assertEquals("你在吗？", ChatAddressing.signed("@AI_Partner", "Alex", "AI_Partner", "!ai ").orElseThrow().text());
        assertTrue(ChatAddressing.signed("AI_Partner2 你好", "Alex", "AI_Partner", "!ai ").isEmpty());
        assertTrue(ChatAddressing.signed("没人叫你", "Alex", "AI_Partner", "!ai ").isEmpty());
    }

    @Test void explicitPrefixAndPublicStopKeepWorking() {
        assertEquals("采集木材", ChatAddressing.signed("!ai 采集木材", "Steve", "AI_Partner", "!ai ").orElseThrow().text());
        assertTrue(ChatAddressing.signed("@AI_Partner 停下", "Steve", "AI_Partner", "!ai ").orElseThrow().stop());
    }

    @Test void signedAndPluginChatRejectTheBotsOwnEcho() {
        List<String> players = List.of("Steve", "AI_Partner");
        assertTrue(ChatAddressing.signed("@AI_Partner 你好", "AI_Partner", "AI_Partner", "!ai ").isEmpty());
        assertTrue(ChatAddressing.decorated("<AI_Partner> 你好", "AI_Partner", "!ai ", players).isEmpty());
        assertTrue(ChatAddressing.decorated("[全服] §aAI_Partner » @AI_Partner 听我说", "AI_Partner", "!ai ", players).isEmpty());
        var custom = ChatAddressing.decorated("[全服] [VIP] §aSteve » @AI_Partner 帮我种田", "AI_Partner", "!ai ", players).orElseThrow();
        assertEquals("Steve", custom.speaker());
        assertEquals("帮我种田", custom.text());
    }

    @Test void ordinaryServerPromptsAreObservedWithoutBecomingPlayerInstructions() {
        List<String> players = List.of("Steve", "AI_Partner");
        assertTrue(ChatAddressing.decorated("Steve 请求传送到 AI_Partner，请输入 /tpaccept", "AI_Partner", "!ai ", players).isEmpty());
        assertEquals("你好", ChatAddressing.decorated("@AI_Partner 你好", "AI_Partner", "!ai ", players).orElseThrow().text());
    }
}
