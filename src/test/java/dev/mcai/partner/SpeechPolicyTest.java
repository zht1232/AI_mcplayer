package dev.mcai.partner;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpeechPolicyTest {
    @Test void changedWordingWithoutProgressCannotSpamWorkUpdates() {
        SpeechPolicy gate = new SpeechPolicy();
        gate.sent("附近有原木，先采集，再检查食物。", 10, true, 2, 0);
        assertFalse(gate.allows("我要先补充木材，然后看看食物够不够。", 1000, true, 2, 0));
        assertTrue(gate.allows("已经采到四块木头。", 1000, true, 2, 1));
        assertFalse(gate.allows("已经采到四块木头。", 100, true, 2, 1));
    }
    @Test void directConversationIsNotBlockedByWorkProgressGate() {
        SpeechPolicy gate = new SpeechPolicy();
        gate.sent("我去采集木材。", 0, true, 1, 0);
        assertTrue(gate.allows("你好，有什么需要我帮忙的？", 60, false, 1, 0));
        gate.sent("你好，有什么需要我帮忙的？", 60, false, 1, 0);
        assertFalse(gate.allows("你好，有什么需要我帮忙的？", 120, false, 1, 0));
        gate.clear();
        assertTrue(gate.allows("你好，有什么需要我帮忙的？", 121, false, 1, 0));
    }
    @Test void minorWordingChangesRemainDuplicatesEvenAfterProgress() {
        SpeechPolicy gate = new SpeechPolicy();
        gate.sent("我看到周围有丛林原木，先收集木材，然后检查食物是否充足。", 0, true, 1, 0);
        assertFalse(gate.allows("我看到周围有丛林原木，先收集木材，再检查食物是否充足。", 700, true, 1, 1));
    }
}
