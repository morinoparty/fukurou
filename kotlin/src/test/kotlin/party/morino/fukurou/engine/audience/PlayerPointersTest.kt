package party.morino.fukurou.engine.audience

import net.kyori.adventure.identity.Identity
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals

class PlayerPointersTest {
    @Test
    @DisplayName("Pointers expose the name, offline UUID, display name and en_US locale")
    fun pointers() {
        val uuid = OfflineUuid.of("Alice")
        val pointers = PlayerPointers.of("Alice", uuid)
        assertEquals("Alice", pointers.get(Identity.NAME).orElseThrow())
        assertEquals(uuid, pointers.get(Identity.UUID).orElseThrow())
        assertEquals(Component.text("Alice"), pointers.get(Identity.DISPLAY_NAME).orElseThrow())
        assertEquals(Locale.US, pointers.get(Identity.LOCALE).orElseThrow())
    }
}
