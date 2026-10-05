package dev.mcai.partner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.mcai.partner.brain.ReasoningProtocol;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PartnerConfig {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public String baseUrl = "http://127.0.0.1:8080/v1";
    public String model = "Qwen3.5-4B";
    /** Direct entry stays on this client; it is never included in game observations. */
    public String apiKey = "";
    public String apiKeyEnv = "";
    public boolean enableThinking = false;
    public String reasoningProtocol = "auto";
    public int maxTokens = 1024;
    public int timeoutSeconds = 120;
    public int decisionIntervalSeconds = 2;
    public int scanRadius = 8;
    public int navigationRange = 32;
    public String publicCommandPrefix = "!ai ";
    public String personality = "你是自然、务实的中文生存队友。简短交流，根据缺少的物资主动安排生存工作。";
    public String survivalGoal = "自主生存：先保证食物与工具，补充木材，适时种田和整理物资。先观察再行动，失败要调整计划。";
    public String notes = "";

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("mc-ai-partner.json");
    }
    public static PartnerConfig load() throws IOException {
        PartnerConfig config = Files.exists(path())
                ? JSON.fromJson(Files.readString(path(), StandardCharsets.UTF_8), PartnerConfig.class)
                : new PartnerConfig();
        if (config == null) throw new IOException("配置内容为空");
        config.validate();
        if (!Files.exists(path())) config.save();
        return config;
    }
    public void validate() {
        if (baseUrl == null || model == null || model.isBlank()) throw new IllegalArgumentException("模型地址和名称不能为空");
        apiKey = apiKey == null ? "" : apiKey.strip();
        apiKeyEnv = apiKeyEnv == null ? "" : apiKeyEnv.strip();
        if (apiKey.length() > 4096 || apiKey.chars().anyMatch(c -> c < 32 || c > 126))
            throw new IllegalArgumentException("API 密钥包含无效字符或过长");
        reasoningProtocol = ReasoningProtocol.fromId(reasoningProtocol).id();
        if (decisionIntervalSeconds < 2 || decisionIntervalSeconds > 120) throw new IllegalArgumentException("规划间隔应为 2–120 秒");
        if (scanRadius < 2 || scanRadius > 12 || navigationRange < 4 || navigationRange > 64) throw new IllegalArgumentException("扫描/导航范围超出限制");
        if (publicCommandPrefix == null || publicCommandPrefix.isBlank()) throw new IllegalArgumentException("公共指令前缀不能为空");
        if (personality == null || survivalGoal == null || notes == null) throw new IllegalArgumentException("人格、目标、笔记不能为 null");
    }
    public void save() throws IOException {
        validate();
        Files.createDirectories(path().getParent());
        Files.writeString(path(), JSON.toJson(this), StandardCharsets.UTF_8);
    }
    public PartnerConfig copy() { return JSON.fromJson(JSON.toJson(this), PartnerConfig.class); }
}
