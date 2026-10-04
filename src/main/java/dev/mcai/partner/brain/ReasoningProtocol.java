package dev.mcai.partner.brain;

import com.google.gson.JsonObject;

import java.net.URI;
import java.util.Locale;

/** Request fields differ between compatible chat APIs; unknown hosts receive no extra fields. */
public enum ReasoningProtocol {
    AUTO("auto", "自动"),
    LLAMA("llama", "llama.cpp"),
    TEMPLATE("template", "模板/vLLM"),
    QWEN("qwen", "通义 API"),
    DEEPSEEK("deepseek", "DeepSeek"),
    NONE("none", "不发送");

    private final String id;
    private final String label;

    ReasoningProtocol(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() { return id; }
    public String label() { return label; }

    public static ReasoningProtocol fromId(String value) {
        if (value == null) throw new IllegalArgumentException("reasoningProtocol must not be null");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ReasoningProtocol protocol : values()) {
            if (protocol.id.equals(normalized)) return protocol;
        }
        throw new IllegalArgumentException("reasoningProtocol must be auto, llama, template, qwen, deepseek or none");
    }

    public ReasoningProtocol resolve(URI endpoint) {
        if (this != AUTO) return this;
        String host = endpoint.getHost();
        if (host == null) return NONE;
        host = host.toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.equals("127.0.0.1")
                || host.equals("[::1]") || host.equals("::1")) return LLAMA;
        if (host.equals("deepseek.com") || host.endsWith(".deepseek.com")) return DEEPSEEK;
        if ((host.equals("aliyuncs.com") || host.endsWith(".aliyuncs.com"))
                && (host.startsWith("dashscope.") || host.startsWith("dashscope-"))) return QWEN;
        return NONE;
    }

    public void apply(JsonObject body, boolean enabled) {
        switch (this) {
            case LLAMA -> {
                body.addProperty("reasoning_effort", enabled ? "medium" : "none");
                addTemplateFlag(body, enabled);
            }
            case TEMPLATE -> addTemplateFlag(body, enabled);
            case QWEN -> body.addProperty("enable_thinking", enabled);
            case DEEPSEEK -> {
                JsonObject thinking = new JsonObject();
                thinking.addProperty("type", enabled ? "enabled" : "disabled");
                body.add("thinking", thinking);
            }
            case NONE -> { /* No documented field for this endpoint. */ }
            case AUTO -> throw new IllegalStateException("Resolve auto reasoning protocol before applying it");
        }
    }

    private static void addTemplateFlag(JsonObject body, boolean enabled) {
        JsonObject template = new JsonObject();
        template.addProperty("enable_thinking", enabled);
        body.add("chat_template_kwargs", template);
    }
}
