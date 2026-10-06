package party.morino.fukurou.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** リクエストの読み取りと応答の書き出し（v3 設計 §1.2）。 */
class ProtocolTest {
    @Test
    @DisplayName("parses id, op, timeoutMs and arguments")
    void parsesRequest() throws AgentException {
        Request request = Protocol.parse("{\"id\":7,\"op\":\"block\",\"world\":\"minecraft:overworld\",\"x\":1,\"y\":-60,\"z\":3,\"timeoutMs\":5000}");
        assertEquals(7, request.id());
        assertEquals("block", request.op());
        assertEquals(5000, request.timeoutMs());
        assertEquals("minecraft:overworld", request.string("world"));
        assertEquals(-60, request.intIn("y", Integer.MIN_VALUE, Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("timeoutMs defaults to 30000")
    void defaultTimeout() throws AgentException {
        assertEquals(30_000, Protocol.parse("{\"id\":1,\"op\":\"ping\"}").timeoutMs());
        assertEquals(30_000, Protocol.parse("{\"id\":1,\"op\":\"ping\",\"timeoutMs\":null}").timeoutMs());
    }

    @Test
    @DisplayName("malformed requests are bad_request with the id when it is known")
    void malformed() {
        AgentException json = assertThrows(AgentException.class, () -> Protocol.parse("{not json"));
        assertEquals(ErrorType.BAD_REQUEST, json.type);
        assertNull(json.requestId);
        assertNull(assertThrows(AgentException.class, () -> Protocol.parse("[1,2]")).requestId);
        assertNull(assertThrows(AgentException.class, () -> Protocol.parse("{\"op\":\"ping\"}")).requestId);
        assertNull(assertThrows(AgentException.class, () -> Protocol.parse("{\"id\":1.5,\"op\":\"ping\"}")).requestId);
        assertEquals(3L, assertThrows(AgentException.class, () -> Protocol.parse("{\"id\":3}")).requestId);
        assertEquals(4L, assertThrows(AgentException.class, () -> Protocol.parse("{\"id\":4,\"op\":1}")).requestId);
        AgentException timeout = assertThrows(AgentException.class, () -> Protocol.parse("{\"id\":5,\"op\":\"ping\",\"timeoutMs\":0}"));
        assertEquals(ErrorType.BAD_REQUEST, timeout.type);
        assertEquals(5L, timeout.requestId);
    }

    @Test
    @DisplayName("argument accessors reject wrong types and out-of-range values")
    void arguments() throws AgentException {
        Request request = Protocol.parse("{\"id\":1,\"op\":\"x\",\"n\":\"1\",\"t\":80000,\"types\":[\"A\",\"b.C\"],\"empty\":[],\"o\":{}}");
        assertEquals(ErrorType.BAD_REQUEST, assertThrows(AgentException.class, () -> request.integer("n")).type);
        assertEquals(ErrorType.BAD_REQUEST, assertThrows(AgentException.class, () -> request.string("missing")).type);
        assertEquals(ErrorType.BAD_REQUEST, assertThrows(AgentException.class, () -> request.intIn("t", 1, 72000)).type);
        assertEquals(ErrorType.BAD_REQUEST, assertThrows(AgentException.class, () -> request.strings("empty")).type);
        assertEquals(ErrorType.BAD_REQUEST, assertThrows(AgentException.class, () -> request.optObject("n")).type);
        assertEquals(List.of("A", "b.C"), request.strings("types"));
        assertTrue(request.json("missing").isJsonNull());
        assertNull(request.optString("missing"));
        assertEquals(new JsonObject(), request.optObject("o"));
    }

    @Test
    @DisplayName("ok responses are single-line JSON")
    void okResponse() {
        JsonObject result = new JsonObject();
        result.addProperty("text", "a\nb <c>");
        result.add("nothing", null);
        String line = Protocol.ok(9, result);
        assertFalse(line.contains("\n"));
        assertEquals("{\"id\":9,\"ok\":true,\"result\":{\"text\":\"a\\nb <c>\",\"nothing\":null}}", line);
        assertEquals("{\"id\":1,\"ok\":true,\"result\":null}", Protocol.ok(1, null));
    }

    @Test
    @DisplayName("error responses carry type, message, exception and stackTrace (null when absent)")
    void errorResponse() {
        JsonObject plain = JsonParser.parseString(Protocol.error(2L, AgentException.notFound("player is not online: Bob"))).getAsJsonObject();
        assertEquals(2, plain.get("id").getAsLong());
        assertFalse(plain.get("ok").getAsBoolean());
        JsonObject error = plain.getAsJsonObject("error");
        assertEquals("not_found", error.get("type").getAsString());
        assertEquals("player is not online: Bob", error.get("message").getAsString());
        assertTrue(error.get("exception").isJsonNull());
        assertTrue(error.get("stackTrace").isJsonNull());

        AgentException failed = AgentException.taskFailed("t", new ExecutionException(new CompletionException(new AssertionError("expected 3"))));
        String line = Protocol.error(3L, failed);
        assertFalse(line.contains("\n"));
        JsonObject task = JsonParser.parseString(line).getAsJsonObject().getAsJsonObject("error");
        assertEquals("task_failed", task.get("type").getAsString());
        assertEquals("java.lang.AssertionError", task.get("exception").getAsString());
        assertTrue(task.get("message").getAsString().contains("expected 3"));
        assertTrue(task.get("stackTrace").getAsString().contains("java.lang.AssertionError: expected 3"));

        JsonObject unknown = JsonParser.parseString(Protocol.error(null, AgentException.badRequest("x"))).getAsJsonObject();
        assertTrue(unknown.get("id").isJsonNull());
    }

    @Test
    @DisplayName("error type names follow the protocol")
    void errorTypeNames() {
        assertEquals("auth", ErrorType.AUTH.wireName);
        assertEquals("bad_request", ErrorType.BAD_REQUEST.wireName);
        assertEquals("not_found", ErrorType.NOT_FOUND.wireName);
        assertEquals("task_failed", ErrorType.TASK_FAILED.wireName);
        assertEquals("timeout", ErrorType.TIMEOUT.wireName);
        assertEquals("internal", ErrorType.INTERNAL.wireName);
    }

    @Test
    @DisplayName("event messages have subscription, type, tick, cancelled and fields")
    void eventMessage() {
        JsonObject fields = new JsonObject();
        fields.add("player", new JsonPrimitive("Alice"));
        String line = Protocol.event(4, "org.bukkit.event.player.PlayerJoinEvent", 120, null, fields);
        assertEquals(
                "{\"event\":{\"subscription\":4,\"type\":\"org.bukkit.event.player.PlayerJoinEvent\",\"tick\":120,\"cancelled\":null,\"fields\":{\"player\":\"Alice\"}}}",
                line);
    }
}
