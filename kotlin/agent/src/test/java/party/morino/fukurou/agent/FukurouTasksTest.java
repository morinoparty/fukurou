package party.morino.fukurou.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import party.morino.fukurou.agent.api.FukurouTask;
import party.morino.fukurou.agent.api.FukurouTasks;

/** static な登録簿（v3 設計 §1.5）。 */
class FukurouTasksTest {
    /** テストで登録した名前（後で外す）。 */
    private static final List<String> NAMES = List.of("test:b", "test:a", "test.c_d-1", "test:dup");

    @AfterEach
    void cleanUp() {
        NAMES.forEach(FukurouTasks::unregister);
    }

    @Test
    @DisplayName("register, find and unregister a task")
    void registerFindUnregister() {
        FukurouTask task = context -> 1;
        FukurouTasks.register("test:a", task);
        assertSame(task, FukurouTasks.find("test:a"));
        assertTrue(FukurouTasks.unregister("test:a"));
        assertNull(FukurouTasks.find("test:a"));
        assertFalse(FukurouTasks.unregister("test:a"));
        assertNull(FukurouTasks.find(null));
        assertFalse(FukurouTasks.unregister(null));
    }

    @Test
    @DisplayName("names are sorted and form an unmodifiable snapshot")
    void namesSorted() {
        FukurouTasks.register("test:b", context -> null);
        FukurouTasks.register("test:a", context -> null);
        FukurouTasks.register("test.c_d-1", context -> null);
        var names = FukurouTasks.names();
        assertEquals(List.of("test.c_d-1", "test:a", "test:b"), List.copyOf(names.subSet("test", "tesu")));
        assertThrows(UnsupportedOperationException.class, () -> names.add("x"));
        FukurouTasks.unregister("test:b");
        assertTrue(names.contains("test:b"), "names() returns a snapshot");
    }

    @Test
    @DisplayName("registering the same name twice throws IllegalStateException")
    void duplicate() {
        FukurouTasks.register("test:dup", context -> null);
        assertThrows(IllegalStateException.class, () -> FukurouTasks.register("test:dup", context -> null));
    }

    @Test
    @DisplayName("invalid names are rejected with IllegalArgumentException")
    void invalidNames() {
        for (String name : List.of("", "-a", ":a", "_a", "a b", "a/b", "ä", "a\n")) {
            assertThrows(IllegalArgumentException.class, () -> FukurouTasks.register(name, context -> null), name);
        }
        assertThrows(NullPointerException.class, () -> FukurouTasks.register(null, context -> null));
        assertThrows(NullPointerException.class, () -> FukurouTasks.register("test:a", null));
    }
}
