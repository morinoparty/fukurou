package party.morino.fukurou.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** プロトコル version 1 のメッセージの読み書き（v3 設計 §1.2）。 */
final class Protocol {
    /** プロトコルの版。 */
    static final int VERSION = 1;

    /** 1 行の上限（16 MiB）。 */
    static final int MAX_LINE_BYTES = 16 * 1024 * 1024;

    /** timeoutMs の既定。 */
    static final long DEFAULT_TIMEOUT_MS = 30_000L;

    /** timeoutMs の上限（1 日）。 */
    static final long MAX_TIMEOUT_MS = 86_400_000L;

    /**
     * メッセージの JSON を書く Gson。null のメンバーも書き（"exception":null など）、1 行に収めるため整形しない。
     * HTML のための {@code <} などのエスケープはしない。
     */
    static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private Protocol() {}

    /**
     * 1 行をリクエストとして読む。
     *
     * @throws AgentException 壊れた JSON・id や op の無いもの（bad_request。id が読めていれば {@link AgentException#requestId} に入る）
     */
    static Request parse(String line) throws AgentException {
        JsonElement element;
        try {
            element = JsonParser.parseString(line);
        } catch (JsonParseException | IllegalStateException e) {
            throw new AgentException(ErrorType.BAD_REQUEST, "malformed JSON: " + e.getMessage(), null, null);
        }
        if (!element.isJsonObject()) {
            throw new AgentException(ErrorType.BAD_REQUEST, "request must be a JSON object", null, null);
        }
        JsonObject body = element.getAsJsonObject();
        Long id = integerOrNull(body.get("id"));
        if (id == null) {
            throw new AgentException(ErrorType.BAD_REQUEST, "request needs an integer \"id\"", null, null);
        }
        JsonElement op = body.get("op");
        if (op == null || !op.isJsonPrimitive() || !op.getAsJsonPrimitive().isString()) {
            throw new AgentException(ErrorType.BAD_REQUEST, "request needs a string \"op\"", null, id);
        }
        long timeoutMs = DEFAULT_TIMEOUT_MS;
        JsonElement timeout = body.get("timeoutMs");
        if (timeout != null && !timeout.isJsonNull()) {
            Long value = integerOrNull(timeout);
            if (value == null || value < 1 || value > MAX_TIMEOUT_MS) {
                throw new AgentException(ErrorType.BAD_REQUEST, "\"timeoutMs\" must be an integer in 1.." + MAX_TIMEOUT_MS, null, id);
            }
            timeoutMs = value;
        }
        return new Request(id, op.getAsString(), body, timeoutMs);
    }

    /** 成功の応答の行。 */
    static String ok(long id, JsonElement result) {
        JsonObject response = new JsonObject();
        response.addProperty("id", id);
        response.addProperty("ok", true);
        response.add("result", result == null ? JsonNull.INSTANCE : result);
        return GSON.toJson(response);
    }

    /** 失敗の応答の行。id が分からなければ {@code "id":null}。 */
    static String error(Long id, AgentException failure) {
        JsonObject error = new JsonObject();
        error.addProperty("type", failure.type.wireName);
        error.addProperty("message", failure.getMessage());
        error.addProperty("exception", failure.remote == null ? null : failure.remote.getClass().getName());
        error.addProperty("stackTrace", failure.remote == null ? null : stackTrace(failure.remote));
        JsonObject response = new JsonObject();
        if (id == null) response.add("id", JsonNull.INSTANCE);
        else response.addProperty("id", id);
        response.addProperty("ok", false);
        response.add("error", error);
        return GSON.toJson(response);
    }

    /** イベントの行。 */
    static String event(long subscription, String type, long tick, Boolean cancelled, JsonObject fields) {
        JsonObject event = new JsonObject();
        event.addProperty("subscription", subscription);
        event.addProperty("type", type);
        event.addProperty("tick", tick);
        event.addProperty("cancelled", cancelled);
        event.add("fields", fields);
        JsonObject message = new JsonObject();
        message.add("event", event);
        return GSON.toJson(message);
    }

    /** ExecutionException / CompletionException / InvocationTargetException の包みを外す。 */
    static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof ExecutionException
                        || current instanceof CompletionException
                        || current instanceof InvocationTargetException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** 例外のスタックトレースの文字列。 */
    static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    /** JSON の整数（小数部の無い数値）。それ以外は null。 */
    static Long integerOrNull(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return null;
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber()) return null;
        try {
            return primitive.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }
}
