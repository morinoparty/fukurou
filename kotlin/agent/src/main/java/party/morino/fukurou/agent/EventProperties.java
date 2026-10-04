package party.morino.fukurou.agent;

import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.bukkit.event.Event;

/**
 * イベントのプロパティ（v3 設計 §1.4）。引数の無い public なメソッドのうち getX / isX を、接頭辞を外して先頭を小文字にした名前で読む。
 *
 * <p>Paper の Adventure 流のアクセサ（AsyncChatEvent の message() など）も読む。引数の無い public なインスタンスメソッドのうち、
 * 戻り値の型が Component に代入できて、名前が getX / isX でなく、Event と Object に同じ名前の引数の無いメソッドが無いものを、
 * メソッド名のままのプロパティにする。副作用のあるメソッドを呼ばないよう、Component を返すものだけに限る。
 * getX / isX と同じ名前になったとき（PlayerJoinEvent の getJoinMessage と joinMessage など）は、Component を返すほうを優先する
 * （Paper では getX のほうが非推奨の文字列版のため）。
 */
final class EventProperties {
    /** プロパティにしないメソッド。 */
    private static final Set<String> EXCLUDED = Set.of("getHandlers", "getHandlerList", "getEventName", "isAsynchronous", "getClass");

    /** 1 つのプロパティ。 */
    record Property(String name, Method method) {}

    /** クラスごとのプロパティの一覧（イベントのたびにリフレクションしないよう覚えておく）。 */
    private static final ClassValue<List<Property>> CACHE = new ClassValue<>() {
        @Override
        protected List<Property> computeValue(Class<?> type) {
            return compute(type);
        }
    };

    /** Adventure の Component の型。読めない環境では null（そのときは Adventure 流のアクセサを読まない）。 */
    private static final Class<?> COMPONENT = loadComponent();

    private EventProperties() {}

    private static Class<?> loadComponent() {
        try {
            return Class.forName("net.kyori.adventure.text.Component", false, EventProperties.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** type のプロパティを名前の順に返す。 */
    static List<Property> of(Class<?> type) {
        return CACHE.get(type);
    }

    /**
     * メソッド名からプロパティ名を作る（getPlayer → player、isCancelled → cancelled）。
     *
     * @return プロパティにならない名前（除外するもの・接頭辞の後が大文字でないもの）なら null
     */
    static String propertyName(String methodName) {
        if (EXCLUDED.contains(methodName)) return null;
        String rest;
        if (methodName.startsWith("get") && methodName.length() > 3) rest = methodName.substring(3);
        else if (methodName.startsWith("is") && methodName.length() > 2) rest = methodName.substring(2);
        else return null;
        char first = rest.charAt(0);
        // getaway / island のような普通の単語は除く
        if (Character.isLowerCase(first)) return null;
        return Character.toLowerCase(first) + rest.substring(1);
    }

    /** event のプロパティの値を JSON にする。値の取得が投げたプロパティは省く。 */
    static JsonObject fields(Object event) {
        JsonObject json = new JsonObject();
        for (Property property : of(event.getClass())) {
            Object value;
            try {
                value = property.method().invoke(event);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                continue;
            }
            try {
                json.add(property.name(), ValueConverter.eventValue(value));
            } catch (RuntimeException | LinkageError e) {
                // 変換できない値は省く
            }
        }
        return json;
    }

    /**
     * Adventure 流のアクセサのプロパティ名（message() → message）。
     *
     * @return 戻り値が Component でない・getX / isX の形・Event か Object にある名前なら null
     */
    static String componentAccessorName(Method method) {
        if (COMPONENT == null || !COMPONENT.isAssignableFrom(method.getReturnType())) return null;
        String name = method.getName();
        if (propertyName(name) != null || EXCLUDED.contains(name)) return null;
        if (declaredOnBase(name)) return null;
        return name;
    }

    /** Event か Object に、同じ名前の引数の無いメソッドがあるか。 */
    private static boolean declaredOnBase(String name) {
        for (Class<?> base : new Class<?>[] {Event.class, Object.class}) {
            try {
                base.getMethod(name);
                return true;
            } catch (NoSuchMethodException e) {
                // 無い
            }
            try {
                base.getDeclaredMethod(name);
                return true;
            } catch (NoSuchMethodException e) {
                // 無い
            }
        }
        return false;
    }

    private static List<Property> compute(Class<?> type) {
        Map<String, Method> byName = new TreeMap<>();
        Map<String, Method> accessors = new TreeMap<>();
        for (Method method : type.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0) continue;
            if (method.isBridge() || method.isSynthetic() || method.getReturnType() == void.class) continue;
            String name = propertyName(method.getName());
            if (name == null) {
                String accessor = componentAccessorName(method);
                if (accessor != null) accessors.putIfAbsent(accessor, method);
                continue;
            }
            // getX と isX が同じ名前になるときは get を優先する
            Method existing = byName.get(name);
            if (existing != null && existing.getName().startsWith("get")) continue;
            byName.put(name, method);
        }
        // getX / isX と同じ名前なら Adventure 流のアクセサを優先する（getJoinMessage は非推奨の文字列版）
        byName.putAll(accessors);
        List<Property> properties = new ArrayList<>(byName.size());
        for (Map.Entry<String, Method> entry : byName.entrySet()) {
            Method method = entry.getValue();
            if (!Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
                // public でないクラスで宣言されたメソッドは、そのままでは呼べない
                try {
                    method.setAccessible(true);
                } catch (RuntimeException e) {
                    continue;
                }
            }
            properties.add(new Property(entry.getKey(), method));
        }
        return Collections.unmodifiableList(properties);
    }
}
