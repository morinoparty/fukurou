package party.morino.fukurou.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 値の JSON への変換（v3 設計 §1.4）。サーバーを起動しない普通の Java の値だけを試す。 */
class ValueConverterTest {
    /** キーを持つ enum（Keyed を enum より先に見ることを試す）。 */
    enum KeyedEnum implements Keyed {
        DIAMOND;

        @Override
        public @NotNull NamespacedKey getKey() {
            return NamespacedKey.minecraft("diamond");
        }
    }

    /** Gson で読めるだけの普通のオブジェクト。 */
    static final class Pojo {
        final String name = "stamp";
        final int count = 3;

        @Override
        public String toString() {
            return "Pojo(stamp)";
        }
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    @Test
    @DisplayName("keeps null, strings, numbers and booleans")
    void primitives() {
        assertTrue(ValueConverter.eventValue(null).isJsonNull());
        assertEquals(json("\"text\""), ValueConverter.eventValue("text"));
        assertEquals(json("\"c\""), ValueConverter.eventValue('c'));
        assertEquals(json("42"), ValueConverter.eventValue(42));
        assertEquals(json("42"), ValueConverter.eventValue(42L));
        assertEquals(1.5, ValueConverter.eventValue(1.5).getAsDouble());
        assertEquals(json("true"), ValueConverter.eventValue(true));
        assertEquals(json("\"NaN\""), ValueConverter.eventValue(Double.NaN));
        assertEquals(json("\"Infinity\""), ValueConverter.eventValue(Float.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("enums become name(), UUIDs strings, Keyed values their key, Components their JSON")
    void specialValues() {
        assertEquals(json("\"MONDAY\""), ValueConverter.eventValue(DayOfWeek.MONDAY));
        assertEquals(json("\"00000000-0000-0001-0000-000000000002\""), ValueConverter.eventValue(new UUID(1, 2)));
        assertEquals(json("\"minecraft:diamond\""), ValueConverter.eventValue(KeyedEnum.DIAMOND));
        // 飾りの無いテキストは Adventure の GSON 形式では文字列になる
        assertEquals(json("\"hi\""), ValueConverter.eventValue(Component.text("hi")));
        JsonObject red = ValueConverter.eventValue(Component.text("hi", NamedTextColor.RED)).getAsJsonObject();
        assertEquals("hi", red.get("text").getAsString());
        assertEquals("red", red.get("color").getAsString());
    }

    @Test
    @DisplayName("collections and arrays are capped at 64 elements")
    void collectionCap() {
        List<Integer> hundred = IntStream.range(0, 100).boxed().toList();
        JsonArray list = ValueConverter.eventValue(hundred).getAsJsonArray();
        assertEquals(64, list.size());
        assertEquals(63, list.get(63).getAsInt());
        assertEquals(64, ValueConverter.eventValue(IntStream.range(0, 100).toArray()).getAsJsonArray().size());
        assertEquals(json("[\"a\",null]"), ValueConverter.eventValue(new String[] {"a", null}));
        assertEquals(json("[1]"), ValueConverter.eventValue(Set.of(1)));
    }

    @Test
    @DisplayName("maps become objects with string keys capped at 64 entries")
    void maps() {
        Map<Object, Object> map = new LinkedHashMap<>();
        map.put("s", 1);
        map.put(2, "two");
        map.put(DayOfWeek.FRIDAY, true);
        map.put(KeyedEnum.DIAMOND, 5);
        map.put(null, "null key");
        assertEquals(json("{\"s\":1,\"2\":\"two\",\"FRIDAY\":true,\"minecraft:diamond\":5,\"null\":\"null key\"}"), ValueConverter.eventValue(map));
        Map<String, Integer> big = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) big.put("k" + i, i);
        JsonObject capped = ValueConverter.eventValue(big).getAsJsonObject();
        assertEquals(64, capped.size());
        assertTrue(capped.has("k63"));
    }

    @Test
    @DisplayName("nesting deeper than 4 levels falls back to toString()")
    void depthCap() {
        // [[[[[1]]]]]: 4 段まではリスト、5 段目のリストは toString()
        Object nested = List.of(List.of(List.of(List.of(List.of(1)))));
        assertEquals(json("[[[[\"[1]\"]]]]"), ValueConverter.eventValue(nested));
        Object fourLevels = List.of(List.of(List.of(List.of(1))));
        assertEquals(json("[[[[1]]]]"), ValueConverter.eventValue(fourLevels));
        // 自分自身を含むリストも止まる
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        JsonElement converted = ValueConverter.eventValue(cyclic);
        assertTrue(converted.isJsonArray());
    }

    @Test
    @DisplayName("execute results pass JsonElement through and fall back to Gson, events to toString()")
    void fallbacks() {
        JsonElement element = json("{\"a\":[1,2]}");
        assertSame(element, ValueConverter.taskValue(element));
        assertEquals(json("{\"name\":\"stamp\",\"count\":3}"), ValueConverter.taskValue(new Pojo()));
        assertEquals(json("\"Pojo(stamp)\""), ValueConverter.eventValue(new Pojo()));
        assertEquals(json("[{\"name\":\"stamp\",\"count\":3}]"), ValueConverter.taskValue(List.of(new Pojo())));
        // Gson で読めない JDK のクラスは toString()
        Thread thread = new Thread(() -> {}, "worker");
        assertEquals(json("\"" + thread + "\""), ValueConverter.taskValue(thread));
    }
}
