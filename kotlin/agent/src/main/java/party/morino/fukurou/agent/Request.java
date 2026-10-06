package party.morino.fukurou.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * 1 つのリクエスト。引数を読むときの誤りは bad_request の {@link AgentException} になる。
 *
 * @param id クライアントが付けた id
 * @param op 操作の名前
 * @param body リクエストの JSON 全体
 * @param timeoutMs メインスレッドでの実行を待つ時間
 */
record Request(long id, String op, JsonObject body, long timeoutMs) {
    /** 必須の文字列の引数。 */
    String string(String name) throws AgentException {
        String value = optString(name);
        if (value == null) throw AgentException.badRequest("\"" + name + "\" is required");
        return value;
    }

    /** 省略できる文字列の引数。 */
    String optString(String name) throws AgentException {
        JsonElement value = body.get(name);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw AgentException.badRequest("\"" + name + "\" must be a string");
        }
        return value.getAsString();
    }

    /** 必須の整数の引数。 */
    long integer(String name) throws AgentException {
        Long value = optInteger(name);
        if (value == null) throw AgentException.badRequest("\"" + name + "\" is required");
        return value;
    }

    /** 省略できる整数の引数。 */
    Long optInteger(String name) throws AgentException {
        JsonElement value = body.get(name);
        if (value == null || value.isJsonNull()) return null;
        Long integer = Protocol.integerOrNull(value);
        if (integer == null) throw AgentException.badRequest("\"" + name + "\" must be an integer");
        return integer;
    }

    /** 範囲を確かめた int の引数。 */
    int intIn(String name, int min, int max) throws AgentException {
        long value = integer(name);
        if (value < min || value > max) {
            throw AgentException.badRequest("\"" + name + "\" must be in " + min + ".." + max + ", got " + value);
        }
        return (int) value;
    }

    /** 省略できるオブジェクトの引数。 */
    JsonObject optObject(String name) throws AgentException {
        JsonElement value = body.get(name);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonObject()) throw AgentException.badRequest("\"" + name + "\" must be an object");
        return value.getAsJsonObject();
    }

    /** 必須の文字列の配列の引数（空は不可）。 */
    List<String> strings(String name) throws AgentException {
        JsonElement value = body.get(name);
        if (value == null || !value.isJsonArray()) throw AgentException.badRequest("\"" + name + "\" must be an array of strings");
        JsonArray array = value.getAsJsonArray();
        if (array.isEmpty()) throw AgentException.badRequest("\"" + name + "\" must not be empty");
        List<String> result = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw AgentException.badRequest("\"" + name + "\" must be an array of strings");
            }
            result.add(element.getAsString());
        }
        return result;
    }

    /** 任意の JSON の引数（無ければ JSON の null）。 */
    JsonElement json(String name) {
        JsonElement value = body.get(name);
        return value == null ? JsonNull.INSTANCE : value;
    }
}
