package dev.mcai.partner;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Public work updates require actual progress; conversational replies stay responsive. */
public final class SpeechPolicy {
    private record Spoken(String text, long tick) {}
    private final ArrayDeque<Spoken> recent = new ArrayDeque<>();
    private long workGeneration = Long.MIN_VALUE, workProgress = -1, workTick = Long.MIN_VALUE;

    public boolean allows(String text, long tick, boolean work, long generation, long progress) {
        if (recent.stream().anyMatch(old -> tick - old.tick < 1200 && similar(old.text, text))) return false;
        if (!recent.isEmpty() && tick - recent.getLast().tick < 40) return false;
        if (!work) return true;
        if (generation == workGeneration && progress <= workProgress) return false;
        return workTick == Long.MIN_VALUE || tick - workTick >= 600;
    }
    public void sent(String text, long tick, boolean work, long generation, long progress) {
        recent.addLast(new Spoken(text, tick));
        while (recent.size() > 12) recent.removeFirst();
        if (work) { workGeneration = generation; workProgress = progress; workTick = tick; }
    }
    public void clear() { recent.clear(); workGeneration = workTick = Long.MIN_VALUE; workProgress = -1; }
    private static boolean similar(String a, String b) {
        a = normalize(a); b = normalize(b);
        if (a.equals(b)) return true;
        if (Math.min(a.length(), b.length()) < 8) return false;
        Set<String> left = grams(a), right = grams(b), intersection = new HashSet<>(left);
        intersection.retainAll(right);
        return 2.0 * intersection.size() / Math.max(1, left.size() + right.size()) >= .64;
    }
    private static String normalize(String text) { return text.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]", ""); }
    private static Set<String> grams(String text) {
        Set<String> values = new HashSet<>();
        for (int i = 0; i + 2 <= text.length(); i++) values.add(text.substring(i, i + 2));
        return values;
    }
}
