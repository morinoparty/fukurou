package party.morino.fukurou.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import party.morino.fukurou.agent.api.FukurouTask;
import party.morino.fukurou.agent.api.FukurouTasks;

/** hello と subscribe / unsubscribe 以外の op（v3 設計 §1.2 の表）。接続ごとのワーカーのスレッドで呼ばれる。 */
final class Operations {
    /** awaitTicks の上限（1 時間）。 */
    static final int MAX_AWAIT_TICKS = 72_000;

    /** entities の limit の既定と上限。 */
    static final int DEFAULT_ENTITY_LIMIT = 256;
    static final int MAX_ENTITY_LIMIT = 4096;

    /** 1 tick の長さ（awaitTicks の待ちの時間の見積もり）。 */
    private static final long TICK_MS = 50;

    private final FukurouAgentPlugin plugin;
    private final MainThread main;

    Operations(FukurouAgentPlugin plugin, MainThread main) {
        this.plugin = plugin;
        this.main = main;
    }

    /** hello の result。 */
    JsonObject hello() {
        JsonObject json = new JsonObject();
        json.addProperty("protocol", Protocol.VERSION);
        json.addProperty("agentVersion", plugin.agentVersion());
        json.addProperty("serverName", Bukkit.getName());
        json.addProperty("serverVersion", Bukkit.getVersion());
        String minecraftVersion;
        try {
            minecraftVersion = Bukkit.getMinecraftVersion();
        } catch (LinkageError e) {
            minecraftVersion = null;
        }
        json.addProperty("minecraftVersion", minecraftVersion);
        return json;
    }

    /** op を実行して result を返す。未知の op は bad_request。 */
    JsonElement handle(Request request) throws AgentException {
        switch (request.op()) {
            case "ping":
                return main.call(() -> tick(Bukkit.getCurrentTick()), request.timeoutMs(), AgentException::internal);
            case "awaitTicks":
                return awaitTicks(request);
            case "player":
                return player(request);
            case "block":
                return block(request);
            case "world":
                return world(request);
            case "entities":
                return entities(request);
            case "plugins":
                return main.call(Operations::plugins, request.timeoutMs(), AgentException::internal);
            case "tasks":
                return tasks();
            case "execute":
                return execute(request);
            default:
                throw AgentException.badRequest("unknown op: " + request.op());
        }
    }

    private static JsonObject tick(long tick) {
        JsonObject json = new JsonObject();
        json.addProperty("tick", tick);
        return json;
    }

    /**
     * 指定の tick 数の後に応答する。待つ時間は {@code ticks × 50 ms + timeoutMs}
     * （72000 tick は既定の 30 秒を超えるので、timeoutMs はサーバーの遅れの余裕として足す）。
     */
    private JsonElement awaitTicks(Request request) throws AgentException {
        int ticks = request.intIn("ticks", 1, MAX_AWAIT_TICKS);
        CompletableFuture<Integer> done = new CompletableFuture<>();
        BukkitTask task;
        try {
            task = Bukkit.getScheduler().runTaskLater(plugin, () -> done.complete(Bukkit.getCurrentTick()), ticks);
        } catch (RuntimeException e) {
            throw new AgentException(ErrorType.INTERNAL, "cannot schedule on the main thread: " + e, e);
        }
        long waitMs = ticks * TICK_MS + request.timeoutMs();
        try {
            return tick(done.get(waitMs, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            task.cancel();
            throw new AgentException(ErrorType.TIMEOUT, ticks + " ticks did not pass within " + waitMs + " ms");
        } catch (ExecutionException e) {
            throw AgentException.internal(Protocol.unwrap(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.cancel();
            throw new AgentException(ErrorType.INTERNAL, "interrupted while waiting for ticks");
        }
    }

    private JsonElement player(Request request) throws AgentException {
        String name = request.string("name");
        return main.call(() -> {
            Player player = Bukkit.getPlayerExact(name);
            if (player == null) throw AgentException.notFound("player is not online: " + name);
            return Snapshots.player(player);
        }, request.timeoutMs(), AgentException::internal);
    }

    private JsonElement block(Request request) throws AgentException {
        String worldKey = request.string("world");
        int x = request.intIn("x", Integer.MIN_VALUE, Integer.MAX_VALUE);
        int y = request.intIn("y", Integer.MIN_VALUE, Integer.MAX_VALUE);
        int z = request.intIn("z", Integer.MIN_VALUE, Integer.MAX_VALUE);
        NamespacedKey key = worldKey(worldKey);
        return main.call(() -> Snapshots.block(world(key).getBlockAt(x, y, z)), request.timeoutMs(), AgentException::internal);
    }

    private JsonElement world(Request request) throws AgentException {
        NamespacedKey key = worldKey(request.string("world"));
        return main.call(() -> Snapshots.world(world(key)), request.timeoutMs(), AgentException::internal);
    }

    private JsonElement entities(Request request) throws AgentException {
        String worldText = request.optString("world");
        NamespacedKey worldKey = worldText == null ? null : worldKey(worldText);
        String typeText = request.optString("type");
        String type = typeText == null ? null : normalizeKey(typeText);
        String tag = request.optString("tag");
        Long limitValue = request.optInteger("limit");
        int limit = limitValue == null ? DEFAULT_ENTITY_LIMIT : (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, limitValue));
        if (limit < 1 || limit > MAX_ENTITY_LIMIT) {
            throw AgentException.badRequest("\"limit\" must be in 1.." + MAX_ENTITY_LIMIT + ", got " + limitValue);
        }
        JsonObject nearJson = request.optObject("near");
        double[] near = nearJson == null ? null : near(nearJson);
        return main.call(() -> {
            List<World> worlds = worldKey == null ? Bukkit.getWorlds() : List.of(world(worldKey));
            JsonArray result = new JsonArray();
            for (World world : worlds) {
                Collection<Entity> candidates;
                if (near == null) {
                    candidates = world.getEntities();
                } else {
                    double r = near[3];
                    candidates = world.getNearbyEntities(new Location(world, near[0], near[1], near[2]), r, r, r);
                }
                for (Entity entity : candidates) {
                    if (result.size() >= limit) return result;
                    if (type != null && !type.equals(Snapshots.key((Object) entity.getType()))) continue;
                    if (tag != null && !entity.getScoreboardTags().contains(tag)) continue;
                    if (near != null) {
                        Location location = entity.getLocation();
                        double dx = location.getX() - near[0];
                        double dy = location.getY() - near[1];
                        double dz = location.getZ() - near[2];
                        if (dx * dx + dy * dy + dz * dz > near[3] * near[3]) continue;
                    }
                    result.add(Snapshots.entity(entity));
                }
            }
            return result;
        }, request.timeoutMs(), AgentException::internal);
    }

    /** near の {"x","y","z","radius"}。 */
    private static double[] near(JsonObject json) throws AgentException {
        double[] values = new double[4];
        String[] names = {"x", "y", "z", "radius"};
        for (int i = 0; i < names.length; i++) {
            JsonElement element = json.get(names[i]);
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                throw AgentException.badRequest("\"near." + names[i] + "\" must be a number");
            }
            values[i] = element.getAsDouble();
            if (!Double.isFinite(values[i])) throw AgentException.badRequest("\"near." + names[i] + "\" must be finite");
        }
        if (values[3] <= 0) throw AgentException.badRequest("\"near.radius\" must be positive");
        return values;
    }

    private static JsonElement plugins() {
        JsonArray json = new JsonArray();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", plugin.getName());
            entry.addProperty("version", pluginVersion(plugin));
            entry.addProperty("enabled", plugin.isEnabled());
            json.add(entry);
        }
        return json;
    }

    /** プラグインの版。PluginDescriptionFile が無くなった版では PluginMeta を使う。 */
    @SuppressWarnings("deprecation")
    static String pluginVersion(Plugin plugin) {
        try {
            return plugin.getDescription().getVersion();
        } catch (RuntimeException | LinkageError e) {
            try {
                return plugin.getPluginMeta().getVersion();
            } catch (RuntimeException | LinkageError e2) {
                return null;
            }
        }
    }

    private static JsonElement tasks() {
        JsonArray json = new JsonArray();
        for (String name : FukurouTasks.names()) json.add(name);
        return json;
    }

    /**
     * タスクをメインスレッドで実行する（v3 設計 §1.5）。戻り値が CompletionStage なら、メインスレッドを離れて完了を待ち、
     * 値の変換だけをもう一度メインスレッドでする。全体で timeoutMs まで。
     */
    private JsonElement execute(Request request) throws AgentException {
        String name = request.string("task");
        JsonElement args = request.json("args");
        FukurouTask task = FukurouTasks.find(name);
        if (task == null) throw AgentException.notFound("task is not registered: " + name);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.timeoutMs());
        TaskContextImpl context = new TaskContextImpl(name, args, plugin.getLogger());
        Object first = main.call(() -> {
            Object value = task.run(context);
            if (value instanceof CompletionStage<?>) return value;
            return new Converted(convertTaskValue(value));
        }, request.timeoutMs(), cause -> AgentException.taskFailed(name, cause));
        JsonElement value;
        if (first instanceof Converted) {
            value = ((Converted) first).json;
        } else {
            CompletableFuture<?> stage = ((CompletionStage<?>) first).toCompletableFuture();
            Object completed;
            try {
                completed = stage.get(remainingMs(deadline), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new AgentException(ErrorType.TIMEOUT, "task " + name + " did not complete within " + request.timeoutMs() + " ms");
            } catch (ExecutionException e) {
                throw AgentException.taskFailed(name, e);
            } catch (CancellationException e) {
                throw AgentException.taskFailed(name, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentException(ErrorType.INTERNAL, "interrupted while waiting for task " + name);
            }
            value = main.call(() -> convertTaskValue(completed), remainingMs(deadline), AgentException::internal);
        }
        JsonObject json = new JsonObject();
        json.add("value", value);
        return json;
    }

    /** 戻り値を JSON にする。変換の失敗はタスクのせいではないので task_failed ではなく internal にする。 */
    static JsonElement convertTaskValue(Object value) throws AgentException {
        try {
            return ValueConverter.taskValue(value);
        } catch (Throwable e) {
            throw AgentException.internal(e);
        }
    }

    /** 変換済みの戻り値（CompletionStage と区別するための包み）。 */
    private static final class Converted {
        final JsonElement json;

        Converted(JsonElement json) {
            this.json = json;
        }
    }

    private static long remainingMs(long deadline) {
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    /** ワールドのキーを読む（形が違えば bad_request）。 */
    private static NamespacedKey worldKey(String text) throws AgentException {
        NamespacedKey key = NamespacedKey.fromString(text.toLowerCase(Locale.ROOT));
        if (key == null) throw AgentException.badRequest("invalid world key: " + text);
        return key;
    }

    /** キーでワールドを引く（無ければ not_found）。メインスレッドで呼ぶ。 */
    private static World world(NamespacedKey key) throws AgentException {
        World world = Bukkit.getWorld(key);
        if (world == null) throw AgentException.notFound("world not found: " + key);
        return world;
    }

    /** 名前空間の無いキーに minecraft: を付ける。 */
    static String normalizeKey(String text) {
        String lower = text.trim().toLowerCase(Locale.ROOT);
        return lower.indexOf(':') >= 0 ? lower : "minecraft:" + lower;
    }
}
