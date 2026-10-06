package party.morino.fukurou.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * イベントのプロパティと execute の戻り値を JSON にする（v3 設計 §1.4）。
 *
 * <ul>
 *   <li>null・文字列・数値・真偽値はそのまま（NaN と無限大は JSON に書けないので文字列）。UUID は文字列。Component は Component の JSON。
 *   <li>Player は名前、その他の Entity は EntitySnapshot、Location・Block・ItemStack はそれぞれのスナップショット、World はキー。
 *   <li>Keyed はキーの文字列。Enum の name() より先に見る（Material などが enum からインターフェースに変わっても同じ出力になるように）。
 *   <li>Collection・配列は最大 {@value #MAX_ELEMENTS} 要素のリスト、Map は文字列のキーの最大 {@value #MAX_ELEMENTS} 要素のオブジェクト。
 *       入れ子は {@value #MAX_DEPTH} 段まで（それより深いものは toString()）。
 * </ul>
 */
final class ValueConverter {
    /** リストとオブジェクトの要素数の上限。 */
    static final int MAX_ELEMENTS = 64;

    /** 入れ子の段数の上限。 */
    static final int MAX_DEPTH = 4;

    private ValueConverter() {}

    /** イベントのプロパティの値。規則に当たらないものは toString()。 */
    static JsonElement eventValue(Object value) {
        return convert(value, 0, false);
    }

    /** execute の戻り値。Gson の JsonElement はそのまま、規則に当たらないものは Gson.toJsonTree（失敗すれば toString()）。 */
    static JsonElement taskValue(Object value) {
        return convert(value, 0, true);
    }

    private static JsonElement convert(Object value, int depth, boolean gsonFallback) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof JsonElement) return (JsonElement) value;
        if (value instanceof String) return new JsonPrimitive((String) value);
        if (value instanceof Character) return new JsonPrimitive(value.toString());
        if (value instanceof Boolean) return new JsonPrimitive((Boolean) value);
        if (value instanceof Number) return number((Number) value);
        if (value instanceof UUID) return new JsonPrimitive(value.toString());
        if (value instanceof Component) return Snapshots.component((Component) value);
        JsonElement bukkit = bukkit(value);
        if (bukkit != null) return bukkit;
        if (value instanceof Enum<?>) return new JsonPrimitive(((Enum<?>) value).name());
        boolean container = value instanceof Collection<?> || value instanceof Map<?, ?> || value.getClass().isArray();
        if (container) {
            if (depth >= MAX_DEPTH) return new JsonPrimitive(String.valueOf(value));
            if (value instanceof Collection<?>) return list(((Collection<?>) value).iterator(), depth, gsonFallback);
            if (value instanceof Map<?, ?>) return map((Map<?, ?>) value, depth, gsonFallback);
            return array(value, depth, gsonFallback);
        }
        if (gsonFallback) {
            try {
                return Protocol.GSON.toJsonTree(value);
            } catch (RuntimeException | StackOverflowError | LinkageError e) {
                // 循環参照や、リフレクションで読めない JDK のクラスなど
            }
        }
        return new JsonPrimitive(String.valueOf(value));
    }

    /** 数値。NaN と無限大は文字列にする。 */
    private static JsonElement number(Number number) {
        if (number instanceof Double || number instanceof Float) {
            double value = number.doubleValue();
            if (Double.isNaN(value) || Double.isInfinite(value)) return new JsonPrimitive(number.toString());
        }
        return new JsonPrimitive(number);
    }

    /** サーバーのオブジェクト。当たらなければ null。 */
    private static JsonElement bukkit(Object value) {
        if (value instanceof Player) return new JsonPrimitive(((Player) value).getName());
        if (value instanceof Entity) return Snapshots.entity((Entity) value);
        if (value instanceof Location) return Snapshots.location((Location) value);
        if (value instanceof Block) return Snapshots.block((Block) value);
        if (value instanceof ItemStack) return Snapshots.item((ItemStack) value);
        if (value instanceof World) return new JsonPrimitive(Snapshots.worldKey((World) value));
        if (value instanceof Keyed) {
            String key = Snapshots.key(value);
            return key != null ? new JsonPrimitive(key) : new JsonPrimitive(String.valueOf(value));
        }
        return null;
    }

    private static JsonArray list(Iterator<?> iterator, int depth, boolean gsonFallback) {
        JsonArray json = new JsonArray();
        while (iterator.hasNext() && json.size() < MAX_ELEMENTS) json.add(convert(iterator.next(), depth + 1, gsonFallback));
        return json;
    }

    private static JsonArray array(Object array, int depth, boolean gsonFallback) {
        JsonArray json = new JsonArray();
        int length = Math.min(Array.getLength(array), MAX_ELEMENTS);
        for (int i = 0; i < length; i++) json.add(convert(Array.get(array, i), depth + 1, gsonFallback));
        return json;
    }

    private static JsonObject map(Map<?, ?> map, int depth, boolean gsonFallback) {
        JsonObject json = new JsonObject();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (json.size() >= MAX_ELEMENTS) break;
            json.add(mapKey(entry.getKey()), convert(entry.getValue(), depth + 1, gsonFallback));
        }
        return json;
    }

    /** Map のキーの文字列。Keyed はキー、Enum は name()、Player は名前、それ以外は toString()。 */
    static String mapKey(Object key) {
        if (key == null) return "null";
        if (key instanceof String) return (String) key;
        if (key instanceof Player) return ((Player) key).getName();
        if (key instanceof World) return Snapshots.worldKey((World) key);
        if (key instanceof Keyed) {
            String keyed = Snapshots.key(key);
            if (keyed != null) return keyed;
        }
        if (key instanceof Enum<?>) return ((Enum<?>) key).name();
        return String.valueOf(key);
    }
}
