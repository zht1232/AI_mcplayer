package dev.mcai.partner;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Text fallback for plugin-formatted chat; signed chat supplies its actual author separately. */
public final class ChatAddressing {
    public record Request(String speaker, String text, boolean stop) {}
    private static final String EDGE = "[A-Za-z0-9_]";
    private ChatAddressing() {}

    public static Optional<Request> signed(String body, String speaker, String ownName, String prefix) {
        if (speaker != null && speaker.equalsIgnoreCase(ownName)) return Optional.empty();
        return content(body, speaker, ownName, prefix);
    }

    public static Optional<Request> decorated(String line, String ownName, String prefix, Collection<String> players) {
        if (line == null) return Optional.empty();
        String clean = line.replaceAll("§[0-9A-FK-ORa-fk-or]", "");
        // TrChat and other chat plugins frequently send ordinary system packets with a decorated author.
        // Locate an online player followed by a chat separator, so the bot's own name in the header
        // never looks like an incoming mention. Long names first avoid matching a shorter prefix.
        for (String player : players.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            Matcher author = Pattern.compile("(?i)(?<!" + EDGE + ")" + Pattern.quote(player)
                    + "(?!" + EDGE + ")\\s*(?:[>»›:：]|\\]\\s*[>»›:：]?)\\s*").matcher(clean);
            if (author.find()) return signed(clean.substring(author.end()), player, ownName, prefix);
        }
        // Unformatted system prompts may contain the player's name (for example TPA).
        // Keep those as observations; only an explicit @ or command prefix addresses us.
        if (ownName == null || ownName.isBlank() || prefix == null || prefix.isBlank()) return Optional.empty();
        boolean explicitAt = Pattern.compile("(?i)(?<!" + EDGE + ")@" + Pattern.quote(ownName) + "(?!" + EDGE + ")").matcher(clean).find();
        boolean explicitPrefix = Pattern.compile("(?:^|[>:\\s：])" + Pattern.quote(prefix.strip()) + "\\s+(.+)$").matcher(clean).find();
        return explicitAt || explicitPrefix ? content(clean, "玩家", ownName, prefix) : Optional.empty();
    }

    private static Optional<Request> content(String body, String speaker, String ownName, String prefix) {
        if (body == null || ownName == null || ownName.isBlank() || prefix == null || prefix.isBlank()) return Optional.empty();
        String text = body.strip();
        String command = prefix.strip();
        Matcher explicit = Pattern.compile("(?:^|[>:\\s：])" + Pattern.quote(command) + "\\s+(.+)$").matcher(text);
        if (explicit.find()) text = explicit.group(1).strip();
        else {
            Pattern mention = Pattern.compile("(?i)(?<!" + EDGE + ")@?" + Pattern.quote(ownName) + "(?!" + EDGE + ")");
            Matcher addressed = mention.matcher(text);
            if (!addressed.find()) return Optional.empty();
            text = mention.matcher(text).replaceAll("").strip().replaceFirst("^[,，:：、\\s]+", "").strip();
            if (text.isBlank()) text = "你在吗？";
        }
        if (text.length() > 512) return Optional.empty();
        boolean stop = text.equalsIgnoreCase("stop") || text.equals("停下") || text.equals("停止");
        return Optional.of(new Request(speaker == null || speaker.isBlank() ? "玩家" : speaker, text, stop));
    }
}
