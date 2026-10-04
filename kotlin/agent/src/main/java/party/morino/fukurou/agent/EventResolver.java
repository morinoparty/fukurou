package party.morino.fukurou.agent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.event.Event;

/** subscribe の types をイベントのクラスにする（v3 設計 §1.2）。 */
final class EventResolver {
    /** 単純名を探すパッケージ（この順）。 */
    static final List<String> PACKAGES = List.of(
            "org.bukkit.event.block",
            "org.bukkit.event.player",
            "org.bukkit.event.entity",
            "org.bukkit.event.inventory",
            "org.bukkit.event.server",
            "org.bukkit.event.world",
            "org.bukkit.event.weather",
            "org.bukkit.event.vehicle",
            "org.bukkit.event.hanging",
            "org.bukkit.event.enchantment",
            "org.bukkit.event.raid",
            "io.papermc.paper.event.player",
            "io.papermc.paper.event.block",
            "io.papermc.paper.event.entity",
            "io.papermc.paper.event.server",
            "io.papermc.paper.event.world",
            "com.destroystokyo.paper.event.player",
            "com.destroystokyo.paper.event.block",
            "com.destroystokyo.paper.event.entity",
            "com.destroystokyo.paper.event.server");

    private EventResolver() {}

    /**
     * 名前をイベントのクラスにする。
     *
     * @param name FQCN か単純名
     * @param serverLoader サーバー（Bukkit / Paper）のクラスを読むクラスローダー
     * @param pluginLoaders FQCN が serverLoader で見つからないときに順に試すクラスローダー（全プラグインのもの）
     * @throws AgentException 見つからない（not_found）、イベントでない・HandlerList を持たない（bad_request）
     */
    static Class<? extends Event> resolve(String name, ClassLoader serverLoader, List<ClassLoader> pluginLoaders)
            throws AgentException {
        if (name == null || name.isBlank()) throw AgentException.badRequest("event type must not be empty");
        Class<?> found = null;
        if (name.indexOf('.') >= 0) {
            found = load(name, serverLoader);
            for (int i = 0; found == null && i < pluginLoaders.size(); i++) found = load(name, pluginLoaders.get(i));
        } else {
            for (int i = 0; found == null && i < PACKAGES.size(); i++) found = load(PACKAGES.get(i) + "." + name, serverLoader);
        }
        if (found == null) throw AgentException.notFound("event class not found: " + name);
        if (!Event.class.isAssignableFrom(found)) throw AgentException.badRequest(found.getName() + " is not a Bukkit event");
        if (!hasHandlerList(found)) {
            throw AgentException.badRequest(found.getName() + " has no static getHandlerList() (abstract events cannot be listened to)");
        }
        return found.asSubclass(Event.class);
    }

    /** type かその親（Event より下）が static な getHandlerList() を宣言しているか。Bukkit の登録と同じ探し方。 */
    static boolean hasHandlerList(Class<?> type) {
        return registrationClass(type) != null;
    }

    /**
     * Bukkit が type のリスナーを登録するクラス（type かその親のうち、static な getHandlerList() を宣言している最初のもの）。
     * SimplePluginManager の getRegistrationClass と同じ探し方。無ければ null。
     */
    static Class<?> registrationClass(Class<?> type) {
        for (Class<?> current = type; current != null && current != Event.class; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod("getHandlerList");
                if (Modifier.isStatic(method.getModifiers())) return current;
            } catch (NoSuchMethodException e) {
                // 親を見る
            }
        }
        return null;
    }

    /**
     * 型を、Bukkit が登録するクラス（＝ 同じ HandlerList）ごとにまとめる。まとまりの順と中の順は types の順。
     *
     * <p>同じ HandlerList に別々のリスナーを登録すると、両方の型に当たるイベント（EntityDamageEvent と
     * EntityDamageByEntityEvent など）が 2 回届く。まとまりごとに 1 つだけ登録し、{@link #matchesAny} で絞れば、
     * 状態を持たずに 1 回だけ送れる（非同期のイベントが同時に起きても重ならない）。
     */
    static List<List<Class<? extends Event>>> groupByRegistration(List<Class<? extends Event>> types) {
        Map<Class<?>, List<Class<? extends Event>>> groups = new LinkedHashMap<>();
        for (Class<? extends Event> type : types) {
            List<Class<? extends Event>> group = groups.computeIfAbsent(registrationClass(type), key -> new ArrayList<>());
            if (!group.contains(type)) group.add(type);
        }
        return new ArrayList<>(groups.values());
    }

    /** event がまとまりのどれかの型に当たるか（HandlerList を共有する別のイベントを除くため）。 */
    static boolean matchesAny(List<Class<? extends Event>> group, Object event) {
        for (Class<? extends Event> type : group) {
            if (type.isInstance(event)) return true;
        }
        return false;
    }

    private static Class<?> load(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
