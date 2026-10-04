package party.morino.fukurou.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** イベントのプロパティの名前と値（v3 設計 §1.4）。 */
class EventPropertiesTest {
    /** 試験用のイベント。 */
    public static final class SampleEvent extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private boolean cancelled = true;

        public String getMessage() {
            return "hello";
        }

        public UUID getId() {
            return new UUID(0, 1);
        }

        public List<Integer> getNumbers() {
            return List.of(1, 2, 3);
        }

        public int getBoom() {
            throw new IllegalStateException("boom");
        }

        public String getaway() {
            return "not a property";
        }

        public String getWith(int argument) {
            return "has an argument";
        }

        public void getNothing() {}

        public boolean isFlag() {
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean cancel) {
            cancelled = cancel;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    /** Paper の Adventure 流のアクセサを持つ試験用のイベント（AsyncChatEvent などに似せる）。 */
    public static final class ChatLikeEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private int sideEffects = 0;

        public Component message() {
            return Component.text("hello v3");
        }

        public TextComponent originalMessage() {
            return Component.text("original");
        }

        public Component joinMessage() {
            return Component.text("component join");
        }

        public String getJoinMessage() {
            return "legacy join";
        }

        public Component getTitle() {
            return Component.text("title");
        }

        public Component boom() {
            throw new IllegalStateException("boom");
        }

        public Component withArgument(int argument) {
            return Component.text("has an argument");
        }

        public static Component staticMessage() {
            return Component.text("static");
        }

        public String plain() {
            sideEffects++;
            return "not a component";
        }

        public void reset() {
            sideEffects++;
        }

        public boolean callEvent() {
            sideEffects++;
            return true;
        }

        public int sideEffects() {
            return sideEffects;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    @Test
    @DisplayName("derives property names from getX and isX")
    void propertyNames() {
        assertEquals("player", EventProperties.propertyName("getPlayer"));
        assertEquals("cancelled", EventProperties.propertyName("isCancelled"));
        assertEquals("uRL", EventProperties.propertyName("getURL"));
        assertEquals("x", EventProperties.propertyName("getX"));
        assertEquals("1", EventProperties.propertyName("get1"));
        assertNull(EventProperties.propertyName("get"));
        assertNull(EventProperties.propertyName("is"));
        assertNull(EventProperties.propertyName("getaway"));
        assertNull(EventProperties.propertyName("island"));
        assertNull(EventProperties.propertyName("player"));
        assertNull(EventProperties.propertyName("setPlayer"));
    }

    @Test
    @DisplayName("excludes getHandlers, getHandlerList, getEventName, isAsynchronous and getClass")
    void excluded() {
        for (String name : List.of("getHandlers", "getHandlerList", "getEventName", "isAsynchronous", "getClass")) {
            assertNull(EventProperties.propertyName(name), name);
        }
    }

    @Test
    @DisplayName("collects public no-arg getters sorted by name and omits throwing ones")
    void fields() {
        JsonObject fields = EventProperties.fields(new SampleEvent());
        assertEquals(List.of("cancelled", "flag", "id", "message", "numbers"), List.copyOf(fields.keySet()));
        assertTrue(fields.get("cancelled").getAsBoolean());
        assertTrue(fields.get("flag").getAsBoolean());
        assertEquals("00000000-0000-0000-0000-000000000001", fields.get("id").getAsString());
        assertEquals("hello", fields.get("message").getAsString());
        JsonArray numbers = fields.getAsJsonArray("numbers");
        assertEquals(3, numbers.size());
        assertFalse(fields.has("boom"));
        assertFalse(fields.has("handlers"));
        assertFalse(fields.has("eventName"));
        assertFalse(fields.has("asynchronous"));
    }

    @Test
    @DisplayName("reads Adventure-style Component accessors by their method name")
    void componentAccessors() {
        ChatLikeEvent event = new ChatLikeEvent();
        JsonObject fields = EventProperties.fields(event);
        assertEquals(List.of("joinMessage", "message", "originalMessage", "title"), List.copyOf(fields.keySet()));
        // 値は Component の JSON
        assertEquals(Snapshots.component(Component.text("hello v3")), fields.get("message"));
        assertEquals(Snapshots.component(Component.text("original")), fields.get("originalMessage"));
        assertEquals(Snapshots.component(Component.text("title")), fields.get("title"));
        // getX と同じ名前になるときは Component を返すほうを優先する
        assertEquals(Snapshots.component(Component.text("component join")), fields.get("joinMessage"));
        // Component を返さないメソッドは呼ばない
        assertEquals(0, event.sideEffects());
    }

    @Test
    @DisplayName("names Component accessors only when they are not getX/isX and not declared on Event or Object")
    void componentAccessorNames() throws NoSuchMethodException {
        assertEquals("message", EventProperties.componentAccessorName(ChatLikeEvent.class.getMethod("message")));
        assertEquals("originalMessage", EventProperties.componentAccessorName(ChatLikeEvent.class.getMethod("originalMessage")));
        assertNull(EventProperties.componentAccessorName(ChatLikeEvent.class.getMethod("getTitle")));
        assertNull(EventProperties.componentAccessorName(ChatLikeEvent.class.getMethod("plain")));
        assertNull(EventProperties.componentAccessorName(ChatLikeEvent.class.getMethod("sideEffects")));
        assertNull(EventProperties.componentAccessorName(Object.class.getMethod("toString")));
    }
}
