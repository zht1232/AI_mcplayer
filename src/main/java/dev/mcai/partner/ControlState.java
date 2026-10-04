package dev.mcai.partner;

import java.util.ArrayDeque;

/** Client-thread state. A local goal owns control until completed or released. */
public final class ControlState {
    public enum Priority { PUBLIC, LOCAL }
    private record Goal(String text, Priority priority) {}
    private final ArrayDeque<Goal> queue = new ArrayDeque<>();
    private boolean connected;
    private boolean enabled;
    private boolean autonomous = true;
    private long generation;
    private Goal current;

    public void connect() { disconnect(); connected = true; }
    public void disconnect() { connected = false; enabled = false; current = null; queue.clear(); generation++; }
    public void enable() {
        if (!connected) throw new IllegalStateException("先加入服务器才能开启 AI");
        enabled = true; generation++;
    }
    public void disable() { enabled = false; current = null; queue.clear(); generation++; }
    public boolean submit(String text, Priority priority) {
        if (!enabled || text == null || text.isBlank()) return false;
        Goal incoming = new Goal(text.strip(), priority);
        if (priority == Priority.PUBLIC && current != null) {
            if (queue.size() >= 8) return false;
            queue.addLast(incoming);
        } else { current = incoming; generation++; }
        return true;
    }
    public boolean publicStop() {
        if (!enabled || localControl()) return false;
        current = null; queue.clear(); autonomous = false; generation++;
        return true;
    }
    public void stop() { current = null; queue.clear(); autonomous = false; generation++; }
    public void complete() { current = queue.pollFirst(); generation++; }
    public void releaseLocal() { if (localControl()) complete(); }
    public void autonomous(boolean value) { autonomous = value; generation++; }
    public boolean connected() { return connected; }
    public boolean enabled() { return enabled; }
    public boolean autonomous() { return autonomous; }
    public boolean localControl() { return current != null && current.priority == Priority.LOCAL; }
    public boolean publicGoal() { return current != null && current.priority == Priority.PUBLIC; }
    public String goal(String fallback) { return current == null ? (autonomous ? fallback : "") : current.text; }
    public long generation() { return generation; }
    public int queued() { return queue.size(); }
}
