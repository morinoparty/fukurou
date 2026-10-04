package party.morino.fukurou.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** subscribe の types の解決（v3 設計 §1.2）。 */
class EventResolverTest {
    private final ClassLoader loader = EventResolverTest.class.getClassLoader();

    private Class<?> resolve(String name) throws AgentException {
        return EventResolver.resolve(name, loader, List.of());
    }

    @Test
    @DisplayName("resolves simple names in the Bukkit and Paper packages")
    void simpleNames() throws AgentException {
        assertEquals("org.bukkit.event.block.BlockBreakEvent", resolve("BlockBreakEvent").getName());
        assertEquals("org.bukkit.event.player.PlayerJoinEvent", resolve("PlayerJoinEvent").getName());
        assertEquals("io.papermc.paper.event.player.AsyncChatEvent", resolve("AsyncChatEvent").getName());
        assertEquals("com.destroystokyo.paper.event.player.PlayerJumpEvent", resolve("PlayerJumpEvent").getName());
    }

    @Test
    @DisplayName("resolves fully qualified names, also through plugin class loaders")
    void fullyQualified() throws AgentException {
        assertEquals("org.bukkit.event.entity.EntityDamageByEntityEvent", resolve("org.bukkit.event.entity.EntityDamageByEntityEvent").getName());
        ClassLoader empty = new ClassLoader(null) {};
        Class<?> viaPlugin = EventResolver.resolve(EventPropertiesTest.SampleEvent.class.getName(), empty, List.of(loader));
        assertEquals(EventPropertiesTest.SampleEvent.class, viaPlugin);
    }

    @Test
    @DisplayName("unknown classes are not_found")
    void notFound() {
        assertEquals(ErrorType.NOT_FOUND, assertThrows(AgentException.class, () -> resolve("NoSuchEvent")).type);
        assertEquals(ErrorType.NOT_FOUND, assertThrows(AgentException.class, () -> resolve("com.example.NoSuchEvent")).type);
    }

    @Test
    @DisplayName("non-events and abstract events without a HandlerList are bad_request")
    void badRequest() {
        assertEquals(ErrorType.BAD_REQUEST, assertThrows(AgentException.class, () -> resolve("java.lang.String")).type);
        assertEquals(ErrorType.BAD_REQUEST, assertThrows(AgentException.class, () -> resolve("PlayerEvent")).type);
        assertEquals(ErrorType.BAD_REQUEST, assertThrows(AgentException.class, () -> resolve("org.bukkit.event.Event")).type);
        assertEquals(ErrorType.BAD_REQUEST, assertThrows(AgentException.class, () -> resolve(" ")).type);
    }

    @Test
    @DisplayName("registration class is the first class declaring a static getHandlerList")
    void registrationClass() {
        assertEquals(EntityDamageEvent.class, EventResolver.registrationClass(EntityDamageEvent.class));
        assertEquals(EntityDamageEvent.class, EventResolver.registrationClass(EntityDamageByEntityEvent.class));
        assertEquals(EntityDamageEvent.class, EventResolver.registrationClass(EntityDamageByBlockEvent.class));
        assertEquals(PlayerJoinEvent.class, EventResolver.registrationClass(PlayerJoinEvent.class));
        assertNull(EventResolver.registrationClass(PlayerEvent.class));
    }

    @Test
    @DisplayName("types sharing a HandlerList are grouped so each event is delivered once")
    void groupsByHandlerList() {
        List<Class<? extends Event>> types = List.of(
                EntityDamageByEntityEvent.class,
                PlayerJoinEvent.class,
                EntityDamageEvent.class,
                EntityDamageByBlockEvent.class,
                PlayerJoinEvent.class);
        assertEquals(
                List.of(
                        List.of(EntityDamageByEntityEvent.class, EntityDamageEvent.class, EntityDamageByBlockEvent.class),
                        List.of(PlayerJoinEvent.class)),
                EventResolver.groupByRegistration(types));
    }

    @Test
    @DisplayName("an event matches a group when it is an instance of any type in it")
    void matchesAny() {
        Object sample = new EventPropertiesTest.SampleEvent();
        assertTrue(EventResolver.matchesAny(List.of(EntityDamageEvent.class, EventPropertiesTest.SampleEvent.class), sample));
        // HandlerList を共有する別のイベント（EntityDamageByBlockEvent だけを購読して EntityDamageEvent が起きた）は除く
        assertFalse(EventResolver.matchesAny(List.of(EntityDamageByBlockEvent.class), sample));
    }
}
