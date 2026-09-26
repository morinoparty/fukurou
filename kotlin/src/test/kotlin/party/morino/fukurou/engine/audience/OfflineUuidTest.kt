package party.morino.fukurou.engine.audience

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals

class OfflineUuidTest {
    @Test
    @DisplayName("Alice gets the offline-mode UUID the server assigns")
    fun alice() {
        // jshell の UUID.nameUUIDFromBytes("OfflinePlayer:Alice") で求めた値
        assertEquals(UUID.fromString("10920508-d5d8-3eed-93d2-92f193afe7d7"), OfflineUuid.of("Alice"))
    }
}
