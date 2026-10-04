package dev.mcai.partner.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AiBrainTest {
    private HttpServer server;
    private String root;
    private volatile JsonObject request;
    private volatile String requestPath;
    private volatile String authorization;
    private volatile String contentType;
    private volatile String response = responseWithSpeech("你好，我准备好了。");
    private volatile int status = 200;
    private volatile CountDownLatch responseGate;
    private final CountDownLatch requestArrived = new CountDownLatch(1);
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        root = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach void stopServer() {
        if (responseGate != null) responseGate.countDown();
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            requestCount.incrementAndGet();
            requestPath = exchange.getRequestURI().getPath();
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            requestArrived.countDown();
            CountDownLatch gate = responseGate;
            if (gate != null) {
                try { gate.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    @Test void sendsBoundedStatelessToolRequestAndParsesNativeCalls() throws Exception {
        response = responseWithCalls("我会打开背包。", call("inspect", "{\"target\":\"inventory\"}"));
        try (AiBrain brain = brain(root + "/v1/", "")) {
            JsonObject observation = new JsonObject();
            observation.addProperty("screen", "箱子商店");
            AiBrain.Decision result = brain.request(observation, "检查库存", List.of("已打开菜单")).get(5, TimeUnit.SECONDS);
            assertEquals("/v1/chat/completions", requestPath);
            assertEquals("application/json", contentType);
            assertNull(authorization);
            assertEquals("test-model", request.get("model").getAsString());
            assertEquals(512, request.get("max_tokens").getAsInt());
            assertFalse(request.get("stream").getAsBoolean());
            assertEquals("auto", request.get("tool_choice").getAsString());
            assertEquals("inspect", request.getAsJsonArray("tools").get(0).getAsJsonObject()
                    .getAsJsonObject("function").get("name").getAsString());
            JsonArray messages = request.getAsJsonArray("messages");
            assertEquals(2, messages.size());
            JsonObject context = JsonParser.parseString(messages.get(1).getAsJsonObject()
                    .get("content").getAsString()).getAsJsonObject();
            assertEquals("检查库存", context.get("goal").getAsString());
            assertEquals("箱子商店", context.getAsJsonObject("observation").get("screen").getAsString());
            assertEquals("已打开菜单", context.getAsJsonArray("recent_results").get(0).getAsString());
            assertEquals("我会打开背包。", result.speech());
            assertEquals(1, result.actions().size());
            assertEquals("inspect", result.actions().getFirst().tool());
            assertEquals("inventory", result.actions().getFirst().args().get("target").getAsString());
        }
    }

    @Test void normalizesRootExplicitBaseAndFullEndpointWithoutRetries() throws Exception {
        for (String base : List.of(root, root + "/v1", root + "/v1/chat/completions/")) {
            try (AiBrain brain = brain(base, "")) {
                brain.request(new JsonObject(), "", List.of()).get(5, TimeUnit.SECONDS);
                assertEquals("/v1/chat/completions", requestPath);
            }
        }
        try (AiBrain brain = brain(root + "/custom/openai", "")) {
            brain.request(new JsonObject(), "", List.of()).get(5, TimeUnit.SECONDS);
            assertEquals("/custom/openai/chat/completions", requestPath);
        }
        assertEquals(4, requestCount.get());
    }

    @Test void plainSpeechNeverBecomesAnActionAndHistoryDoesNotAccumulate() {
        response = responseWithSpeech("{\"tool\":\"inspect\",\"args\":{}}");
        try (AiBrain brain = brain(root, "")) {
            AiBrain.Decision first = brain.request(new JsonObject(), "第一次", List.of("旧结果")).join();
            assertTrue(first.actions().isEmpty());
            brain.request(new JsonObject(), "第二次", List.of()).join();
            JsonArray messages = request.getAsJsonArray("messages");
            assertEquals(2, messages.size());
            assertFalse(messages.toString().contains("旧结果"));
        }
    }

    @Test void serializesThinkingOnAndOffForEachSupportedBackend() throws Exception {
        for (String protocol : List.of("llama", "template", "qwen", "deepseek", "none")) {
            for (boolean enabled : List.of(false, true)) {
                try (AiBrain brain = new AiBrain(new AiBrain.Options(root, "test-model", "", 512, 5,
                        enabled, protocol), List.of(toolSchema()))) {
                    brain.request(new JsonObject(), "问候", List.of()).get(5, TimeUnit.SECONDS);
                }
                switch (protocol) {
                    case "llama", "template" -> {
                        assertEquals(enabled, request.getAsJsonObject("chat_template_kwargs")
                                .get("enable_thinking").getAsBoolean());
                        assertFalse(request.has("enable_thinking"));
                        assertFalse(request.has("thinking"));
                        if (protocol.equals("llama")) {
                            assertEquals(enabled ? "medium" : "none", request.get("reasoning_effort").getAsString());
                        } else {
                            assertFalse(request.has("reasoning_effort"));
                        }
                    }
                    case "qwen" -> {
                        assertEquals(enabled, request.get("enable_thinking").getAsBoolean());
                        assertFalse(request.has("chat_template_kwargs"));
                        assertFalse(request.has("reasoning_effort"));
                        assertFalse(request.has("thinking"));
                    }
                    case "deepseek" -> {
                        assertEquals(enabled ? "enabled" : "disabled", request.getAsJsonObject("thinking")
                                .get("type").getAsString());
                        assertFalse(request.has("chat_template_kwargs"));
                        assertFalse(request.has("reasoning_effort"));
                        assertFalse(request.has("enable_thinking"));
                    }
                    case "none" -> assertNoThinkingFields(request);
                }
            }
        }
        assertEquals(10, requestCount.get());
    }

    @Test void legacyOptionsDisableThinkingOnLocalAutoProtocol() throws Exception {
        AiBrain.Options defaults = new AiBrain.Options(root, "test-model", "", 512, 5);
        assertFalse(defaults.enableThinking());
        assertEquals("auto", defaults.reasoningProtocol());
        try (AiBrain brain = new AiBrain(defaults, List.of(toolSchema()))) {
            brain.request(new JsonObject(), "", List.of()).get(5, TimeUnit.SECONDS);
        }
        assertEquals("none", request.get("reasoning_effort").getAsString());
        assertFalse(request.getAsJsonObject("chat_template_kwargs").get("enable_thinking").getAsBoolean());
    }

    @Test void autoProtocolRecognizesOnlyKnownHostsAndOmitsUnknownFields() {
        for (String host : List.of("localhost", "127.0.0.1", "[::1]")) {
            assertEquals(ReasoningProtocol.LLAMA, ReasoningProtocol.AUTO.resolve(URI.create("http://" + host + ":8080/v1")));
        }
        assertEquals(ReasoningProtocol.DEEPSEEK,
                ReasoningProtocol.AUTO.resolve(URI.create("https://api.deepseek.com/v1")));
        assertEquals(ReasoningProtocol.QWEN,
                ReasoningProtocol.AUTO.resolve(URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1")));
        assertEquals(ReasoningProtocol.QWEN,
                ReasoningProtocol.AUTO.resolve(URI.create("https://dashscope-intl.aliyuncs.com/compatible-mode/v1")));
        for (String host : List.of("api.example.com", "deepseek.com.evil.example", "dashscope.aliyuncs.com.evil.example")) {
            ReasoningProtocol selected = ReasoningProtocol.AUTO.resolve(URI.create("https://" + host + "/v1"));
            assertEquals(ReasoningProtocol.NONE, selected);
            for (boolean enabled : List.of(false, true)) {
                JsonObject body = new JsonObject();
                body.addProperty("model", "test-model");
                selected.apply(body, enabled);
                assertNoThinkingFields(body);
                assertEquals(1, body.size());
            }
        }
    }

    @Test void validatesProtocolAndNeverTurnsHiddenReasoningIntoSpeech() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new AiBrain.Options(root, "model", "", 512, 5, false, "unknown"));
        assertThrows(IllegalArgumentException.class,
                () -> new AiBrain.Options(root, "model", "", 512, 5, false, null));
        assertEquals("template", new AiBrain.Options(root, "model", "", 512, 5,
                true, " TEMPLATE ").reasoningProtocol());
        JsonObject envelope = JsonParser.parseString(responseWithSpeech("你好。")).getAsJsonObject();
        envelope.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message")
                .addProperty("reasoning_content", "private intermediate reasoning");
        response = envelope.toString();
        try (AiBrain brain = brain(root, "")) {
            AiBrain.Decision decision = brain.request(new JsonObject(), "", List.of()).get(5, TimeUnit.SECONDS);
            assertEquals("你好。", decision.speech());
            assertTrue(decision.actions().isEmpty());
        }
    }

    private static void assertNoThinkingFields(JsonObject body) {
        assertFalse(body.has("chat_template_kwargs"));
        assertFalse(body.has("reasoning_effort"));
        assertFalse(body.has("enable_thinking"));
        assertFalse(body.has("thinking"));
    }

    @Test void rejectsUnknownToolsMalformedArgumentsAndTooManyCalls() {
        try (AiBrain brain = brain(root, "")) {
            response = responseWithCalls("", call("invented_tool", "{}"));
            assertFailure(brain, "unknown tool");
            for (String args : List.of("{broken", "[]", "null", "{\"x\":1,}", "{} {}", "{x:1}")) {
                response = responseWithCalls("", call("inspect", args));
                assertFailure(brain, "Malformed JSON arguments");
            }
            response = responseWithCalls("", call("inspect", "{}"), call("inspect", "{}"),
                    call("inspect", "{}"), call("inspect", "{}"));
            assertFailure(brain, "more than three");
        }
    }

    @Test void rejectsMalformedResponseAndTruncation() {
        try (AiBrain brain = brain(root, "")) {
            response = "not json";
            assertFailure(brain, "Malformed OpenAI-compatible");
            response = "{\"choices\":[]}";
            assertFailure(brain, "no choices");
            response = "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}";
            assertFailure(brain, "truncated");
        }
    }

    @Test void reportsHttpFailureAndNeverRetries() {
        status = 429;
        response = "{\"error\":{\"message\":\"quota exhausted\"}}";
        try (AiBrain brain = brain(root, "")) {
            assertFailure(brain, "HTTP 429: quota exhausted");
            assertEquals(1, requestCount.get());
        }
    }

    @Test void readsConfiguredEnvironmentVariableAndRedactsItFromHttpErrors() {
        String variable = System.getenv("USERNAME") != null ? "USERNAME" : "USER";
        String value = System.getenv(variable);
        assumeTrue(value != null && !value.isBlank());
        status = 401;
        JsonObject error = new JsonObject();
        error.addProperty("message", "Invalid bearer: " + value);
        JsonObject envelope = new JsonObject();
        envelope.add("error", error);
        response = envelope.toString();
        try (AiBrain brain = brain(root, variable)) {
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> brain.request(new JsonObject(), "", List.of()).join());
            assertEquals("Bearer " + value, authorization);
            assertTrue(failure.getCause().getMessage().contains("[redacted]"));
            assertFalse(failure.getCause().getMessage().contains("Invalid bearer: " + value));
        }
    }

    @Test void missingKeyFailsBeforeSendingRequest() {
        try (AiBrain brain = brain(root, "MCAI_TEST_NONEXISTENT_KEY_C6829A30")) {
            assertFailure(brain, "environment variable is not set");
            assertEquals(0, requestCount.get());
        }
    }

    @Test void preventsOverlappingRequestsAndCancelsOnClose() throws Exception {
        responseGate = new CountDownLatch(1);
        AiBrain brain = brain(root, "");
        CompletableFuture<AiBrain.Decision> first = brain.request(new JsonObject(), "", List.of());
        assertTrue(requestArrived.await(5, TimeUnit.SECONDS));
        assertFailure(brain, "already running");
        brain.close();
        assertTrue(first.isCompletedExceptionally());
        assertFailure(brain, "closed");
        assertEquals(1, requestCount.get());
    }

    @Test void boundsResponseSize() {
        response = "x".repeat(1024 * 1024 + 1);
        try (AiBrain brain = brain(root, "")) {
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> brain.request(new JsonObject(), "", List.of()).join());
            Throwable cause = failure;
            boolean foundLimit = false;
            while (cause != null) {
                if (cause.getMessage() != null && cause.getMessage().contains("1 MiB")) foundLimit = true;
                cause = cause.getCause();
            }
            assertTrue(foundLimit);
        }
    }

    @Test void boundsRequestSizeBeforeHttp() {
        JsonObject observation = new JsonObject();
        observation.addProperty("unbounded_chat", "x".repeat(256 * 1024));
        try (AiBrain brain = brain(root, "")) {
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> brain.request(observation, "", List.of()).join());
            assertTrue(failure.getCause().getMessage().contains("256 KiB"));
            assertEquals(0, requestCount.get());
        }
    }

    private AiBrain brain(String base, String environmentVariable) {
        return new AiBrain(new AiBrain.Options(base, "test-model", environmentVariable, 512, 5),
                List.of(toolSchema()));
    }

    private static JsonObject toolSchema() {
        JsonObject function = new JsonObject();
        function.addProperty("name", "inspect");
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        function.add("parameters", parameters);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "function");
        schema.add("function", function);
        return schema;
    }

    private static JsonObject call(String name, String arguments) {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("arguments", arguments);
        JsonObject call = new JsonObject();
        call.addProperty("id", "test-call");
        call.addProperty("type", "function");
        call.add("function", function);
        return call;
    }

    private static String responseWithSpeech(String speech) {
        return responseWithCalls(speech);
    }

    private static String responseWithCalls(String speech, JsonObject... calls) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", speech);
        if (calls.length > 0) {
            JsonArray toolCalls = new JsonArray();
            for (JsonObject call : calls) toolCalls.add(call);
            message.add("tool_calls", toolCalls);
        }
        JsonObject choice = new JsonObject();
        choice.addProperty("finish_reason", calls.length > 0 ? "tool_calls" : "stop");
        choice.add("message", message);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject envelope = new JsonObject();
        envelope.add("choices", choices);
        return envelope.toString();
    }

    private static void assertFailure(AiBrain brain, String expectedMessage) {
        CompletionException failure = assertThrows(CompletionException.class,
                () -> brain.request(new JsonObject(), "", List.of()).join());
        assertInstanceOf(AiBrain.BrainException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains(expectedMessage), failure.getCause().getMessage());
    }
}
