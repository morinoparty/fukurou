package party.morino.fukurou.e2e.companion;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import party.morino.fukurou.agent.api.FukurouTask;
import party.morino.fukurou.agent.api.FukurouTasks;
import party.morino.fukurou.agent.api.TaskContext;

/**
 * fukurou の e2e のためのタスクを登録する。
 *
 * <ul>
 *   <li>{@code e2e:echo}: 引数の JSON をそのまま返す。
 *   <li>{@code e2e:count-blocks}: {@code {world,x1,y1,z1,x2,y2,z2,type}} の直方体（両端を含む）にある type のブロックの数。
 *   <li>{@code e2e:fail}: {@code IllegalStateException("boom")} を投げる。
 *   <li>{@code e2e:online}: オンラインのプレイヤー名（名前の順）。
 *   <li>{@code e2e:async}: 次の tick に、その時点の tick で完了する CompletableFuture。
 *   <li>{@code e2e:set-flag}: {@code {"key"?, "value"}}（または文字列）の値を覚える。key の既定は "default"。
 *   <li>{@code e2e:get-flag}: {@code {"key"?}}（または key の文字列、または null）の値を返す。無ければ null。
 * </ul>
 */
public final class FukurouE2eCompanionPlugin extends JavaPlugin {
    /** e2e:set-flag / e2e:get-flag の key の既定。 */
    private static final String DEFAULT_FLAG = "default";

    /** 登録したタスクの名前（無効化のときに外す）。 */
    private final List<String> registered = new ArrayList<>();

    /** e2e:set-flag で覚えた値。 */
    private final Map<String, String> flags = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        register("e2e:echo", context -> JsonParser.parseString(context.argsJson()));
        register("e2e:count-blocks", this::countBlocks);
        register("e2e:fail", context -> {
            throw new IllegalStateException("boom");
        });
        register("e2e:online", context -> {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) names.add(player.getName());
            names.sort(null);
            return names;
        });
        register("e2e:async", context -> {
            CompletableFuture<Integer> future = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(this, () -> future.complete(Bukkit.getCurrentTick()));
            return future;
        });
        register("e2e:set-flag", context -> {
            JsonElement args = JsonParser.parseString(context.argsJson());
            String key = DEFAULT_FLAG;
            String value;
            if (args.isJsonObject()) {
                JsonObject object = args.getAsJsonObject();
                if (object.has("key") && !object.get("key").isJsonNull()) key = object.get("key").getAsString();
                JsonElement raw = object.get("value");
                value = raw == null || raw.isJsonNull() ? null : raw.getAsString();
            } else {
                value = args.isJsonNull() ? null : args.getAsString();
            }
            if (value == null) flags.remove(key);
            else flags.put(key, value);
            return null;
        });
        register("e2e:get-flag", context -> {
            JsonElement args = JsonParser.parseString(context.argsJson());
            String key = DEFAULT_FLAG;
            if (args.isJsonObject()) {
                JsonElement raw = args.getAsJsonObject().get("key");
                if (raw != null && !raw.isJsonNull()) key = raw.getAsString();
            } else if (!args.isJsonNull()) {
                key = args.getAsString();
            }
            String value = flags.get(key);
            return value == null ? JsonNull.INSTANCE : value;
        });
    }

    @Override
    public void onDisable() {
        registered.forEach(FukurouTasks::unregister);
        registered.clear();
        flags.clear();
    }

    private void register(String name, FukurouTask task) {
        FukurouTasks.register(name, task);
        registered.add(name);
    }

    /** e2e:count-blocks の引数。 */
    static final class CountArgs {
        String world;
        int x1;
        int y1;
        int z1;
        int x2;
        int y2;
        int z2;
        String type;
    }

    private Object countBlocks(TaskContext context) {
        CountArgs args = context.args(CountArgs.class);
        if (args == null || args.world == null || args.type == null) {
            throw new IllegalArgumentException("e2e:count-blocks needs {world,x1,y1,z1,x2,y2,z2,type}");
        }
        NamespacedKey worldKey = NamespacedKey.fromString(args.world);
        World world = worldKey == null ? null : Bukkit.getWorld(worldKey);
        if (world == null) throw new IllegalArgumentException("world not found: " + args.world);
        String type = args.type.toLowerCase(Locale.ROOT);
        if (type.indexOf(':') < 0) type = "minecraft:" + type;
        int count = 0;
        for (int x = Math.min(args.x1, args.x2); x <= Math.max(args.x1, args.x2); x++) {
            for (int y = Math.min(args.y1, args.y2); y <= Math.max(args.y1, args.y2); y++) {
                for (int z = Math.min(args.z1, args.z2); z <= Math.max(args.z1, args.z2); z++) {
                    // Material を enum として扱わず Keyed のキーで比べる（新しい版でも動くように）
                    Object material = world.getBlockAt(x, y, z).getType();
                    if (material instanceof Keyed && type.equals(((Keyed) material).getKey().toString())) count++;
                }
            }
        }
        return count;
    }
}
