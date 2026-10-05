package dev.mcai.partner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeSkillBookTest {
    @TempDir Path root;
    @Test void learnedKnowledgeSurvivesRestartAndIsServerScoped() throws Exception {
        var book = new RuntimeSkillBook(root);
        book.feedback("server-one", "collect_nearby: FAILED: no visible dropped item nearby");
        book.feedback("server-one", "collect_nearby: FAILED: no visible dropped item nearby");
        var restarted = new RuntimeSkillBook(root);
        assertTrue(restarted.relevant("server-one", "收木头", "world").toString().contains("只能拾取"));
        assertFalse(restarted.relevant("server-two", "收木头", "world").toString().contains("只能拾取"));
        assertTrue(Files.exists(root.resolve("SKILL.md")));
        assertTrue(Files.exists(root.resolve("combat/SKILL.md")));
    }
    @Test void modelCannotOverwriteBaseSkillsOrSaveCredentials() throws Exception {
        var book = new RuntimeSkillBook(root);
        String original = Files.readString(root.resolve("ui/SKILL.md"));
        assertTrue(book.learn("server", "../../outside", "bad").startsWith("FAILED"));
        assertTrue(book.learn("server", "ui", "password secret").startsWith("FAILED"));
        assertEquals(original, Files.readString(root.resolve("ui/SKILL.md")));
        assertTrue(book.learn("server", "server", "此服商店通过聊天按钮打开。").startsWith("OK"));
    }
}
