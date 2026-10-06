package party.morino.fukurou.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

/**
 * 1 本の TCP 接続（v3 設計 §1.2）。
 *
 * <p>読み取りのスレッドが行を読み、hello の後はリクエストをワーカーに渡す（遅い execute や awaitTicks が他のリクエストを止めないように。
 * 応答は id で対応づけるので順序は問わない）。書き込みは接続ごとの送信キューと専用のスレッドがする。
 * メインスレッドやイベントのスレッドはキューに積むだけで、ソケットには触れない。
 */
final class AgentConnection {
    /** 送信キューに積むと書き込みのスレッドが接続を閉じる印。 */
    private static final byte[] CLOSE = new byte[0];

    /** 送信キューに溜めてよいイベントの数（超えたら捨てる）。応答は捨てない。 */
    private static final int MAX_PENDING_EVENTS = 100_000;

    private final Socket socket;
    private final Plugin plugin;
    private final Logger logger;
    private final String token;
    private final Operations operations;
    private final MainThread main;
    private final ExecutorService workers;
    private final Runnable onClosed;
    private final BlockingQueue<byte[]> outgoing = new LinkedBlockingQueue<>();
    private final Map<Long, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean droppedWarned = new AtomicBoolean();
    private final AtomicBoolean eventFailureWarned = new AtomicBoolean();

    /** 1 つの購読。リスナーは購読ごとに 1 つ作り、外すときに HandlerList.unregisterAll で消す。 */
    private static final class Subscription {
        final Listener listener = new Listener() {};
    }

    AgentConnection(
            Socket socket,
            Plugin plugin,
            String token,
            Operations operations,
            MainThread main,
            ExecutorService workers,
            Runnable onClosed) {
        this.socket = socket;
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.token = token;
        this.operations = operations;
        this.main = main;
        this.workers = workers;
        this.onClosed = onClosed;
    }

    /** 読み取りと書き込みのスレッドを始める。 */
    void start() {
        String remote = String.valueOf(socket.getRemoteSocketAddress());
        Thread writer = new Thread(this::writeLoop, "FukurouAgent-writer " + remote);
        writer.setDaemon(true);
        writer.start();
        Thread reader = new Thread(this::readLoop, "FukurouAgent-reader " + remote);
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop() {
        try {
            LineReader reader = new LineReader(socket.getInputStream(), Protocol.MAX_LINE_BYTES);
            if (!handshake(reader)) return;
            while (!closed.get()) {
                String line = reader.readLine();
                if (line == null) break;
                if (line.isBlank()) continue;
                Request request;
                try {
                    request = Protocol.parse(line);
                } catch (AgentException e) {
                    send(Protocol.error(e.requestId, e));
                    continue;
                }
                try {
                    workers.execute(() -> dispatch(request));
                } catch (RejectedExecutionException e) {
                    // エージェントの停止中
                    break;
                }
            }
        } catch (LineReader.LineTooLongException e) {
            send(Protocol.error(null, AgentException.badRequest(e.getMessage())));
        } catch (IOException e) {
            // 相手が切った、または close() でソケットを閉じた
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "agent connection failed", e);
        } finally {
            close();
        }
    }

    /** 最初のメッセージの hello を確かめる。通れば true。通らなければ応答を送って false（呼び元が閉じる）。 */
    private boolean handshake(LineReader reader) throws IOException {
        String line;
        do {
            line = reader.readLine();
            if (line == null) return false;
        } while (line.isBlank());
        Request request;
        try {
            request = Protocol.parse(line);
        } catch (AgentException e) {
            send(Protocol.error(e.requestId, e));
            return false;
        }
        if (!request.op().equals("hello")) {
            send(Protocol.error(request.id(), new AgentException(ErrorType.AUTH, "the first message must be hello")));
            return false;
        }
        String given = request.body().has("token") && request.body().get("token").isJsonPrimitive()
                ? request.body().get("token").getAsString()
                : "";
        if (!MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            send(Protocol.error(request.id(), new AgentException(ErrorType.AUTH, "invalid token")));
            return false;
        }
        Long protocol = Protocol.integerOrNull(request.body().get("protocol"));
        if (protocol == null || protocol != Protocol.VERSION) {
            send(Protocol.error(request.id(), AgentException.badRequest(
                    "unsupported protocol " + request.body().get("protocol") + " (agent speaks " + Protocol.VERSION + ")")));
            return false;
        }
        // hello はメインスレッドを通さずに答える（load: STARTUP の時点ではまだ tick が回っていない）
        sendResponse(request.id(), operations.hello());
        return true;
    }

    /** ワーカーのスレッドで 1 つのリクエストを処理する。 */
    private void dispatch(Request request) {
        try {
            JsonElement result;
            switch (request.op()) {
                case "hello":
                    throw AgentException.badRequest("already authenticated");
                case "subscribe":
                    result = subscribe(request);
                    break;
                case "unsubscribe":
                    result = unsubscribe(request);
                    break;
                default:
                    result = operations.handle(request);
                    break;
            }
            sendResponse(request.id(), result);
        } catch (AgentException e) {
            send(Protocol.error(request.id(), e));
        } catch (Throwable e) {
            // 応答を返さないとクライアントは期限まで待ち続けるので、Error も internal にして返す
            send(Protocol.error(request.id(), AgentException.internal(e)));
        }
    }

    // ---- 購読 ----

    private JsonElement subscribe(Request request) throws AgentException {
        long id = request.integer("subscription");
        List<String> names = request.strings("types");
        List<ClassLoader> pluginLoaders = new ArrayList<>();
        for (Plugin other : Bukkit.getPluginManager().getPlugins()) pluginLoaders.add(other.getClass().getClassLoader());
        ClassLoader serverLoader = AgentConnection.class.getClassLoader();
        // result の types は要求の順に 1 つずつ返す。登録は HandlerList（Bukkit が登録するクラス）ごとに 1 回だけにする
        List<Class<? extends Event>> requested = new ArrayList<>(names.size());
        // 1 つでも解決できなければ何も登録しない
        for (String name : names) requested.add(EventResolver.resolve(name, serverLoader, pluginLoaders));
        List<List<Class<? extends Event>>> groups = EventResolver.groupByRegistration(requested);
        Subscription subscription = new Subscription();
        if (subscriptions.putIfAbsent(id, subscription) != null) {
            throw AgentException.badRequest("subscription " + id + " already exists");
        }
        try {
            main.call(() -> {
                for (List<Class<? extends Event>> group : groups) {
                    Bukkit.getPluginManager()
                            .registerEvent(group.get(0), subscription.listener, EventPriority.MONITOR, executor(id, group), plugin, false);
                }
                // 登録の前に接続が閉じた・unsubscribe された・待ちが切れて外された場合、ここで外さないと残り続ける
                if (closed.get() || subscriptions.get(id) != subscription) HandlerList.unregisterAll(subscription.listener);
                return null;
            }, request.timeoutMs(), cause -> AgentException.badRequest("cannot listen to events: " + cause));
        } catch (AgentException e) {
            // 先に購読の一覧から外してから登録を消す（メインスレッドの側の確かめと合わせて、どの順で動いても残らない）
            subscriptions.remove(id, subscription);
            HandlerList.unregisterAll(subscription.listener);
            throw e;
        }
        JsonArray resolved = new JsonArray();
        for (Class<? extends Event> type : requested) resolved.add(type.getName());
        JsonObject json = new JsonObject();
        json.add("types", resolved);
        return json;
    }

    private JsonElement unsubscribe(Request request) throws AgentException {
        long id = request.integer("subscription");
        Subscription subscription = subscriptions.remove(id);
        if (subscription != null) HandlerList.unregisterAll(subscription.listener);
        return new JsonObject();
    }

    /** イベントを直列化して送信キューに積む EventExecutor。イベントの起きたスレッドで動く。 */
    private EventExecutor executor(long id, List<Class<? extends Event>> group) {
        return (listener, event) -> {
            // HandlerList を共有する別のイベントは除く。同じ HandlerList の型はまとめて 1 つの executor にしたので 1 回だけ送る
            if (!EventResolver.matchesAny(group, event) || closed.get()) return;
            try {
                Boolean cancelled = event instanceof Cancellable ? ((Cancellable) event).isCancelled() : null;
                JsonObject fields = EventProperties.fields(event);
                String line = Protocol.event(id, event.getClass().getName(), Bukkit.getCurrentTick(), cancelled, fields);
                sendEvent(line);
            } catch (RuntimeException | LinkageError e) {
                if (eventFailureWarned.compareAndSet(false, true)) {
                    logger.log(Level.WARNING, "cannot serialize " + event.getClass().getName(), e);
                }
            }
        };
    }

    // ---- 送信 ----

    private void sendResponse(long id, JsonElement result) {
        String line = Protocol.ok(id, result);
        if (utf8Length(line) > Protocol.MAX_LINE_BYTES) {
            line = Protocol.error(id, new AgentException(ErrorType.INTERNAL, "response exceeds " + Protocol.MAX_LINE_BYTES + " bytes"));
        }
        send(line);
    }

    private void sendEvent(String line) {
        if (utf8Length(line) > Protocol.MAX_LINE_BYTES || outgoing.size() >= MAX_PENDING_EVENTS) {
            if (droppedWarned.compareAndSet(false, true)) logger.warning("dropping events: the client is not reading fast enough or an event is too large");
            return;
        }
        send(line);
    }

    private void send(String line) {
        if (closed.get()) return;
        outgoing.add(line.getBytes(StandardCharsets.UTF_8));
    }

    private static long utf8Length(String line) {
        // 文字数の 3 倍以下なら上限に届かないので数えない
        if ((long) line.length() * 3 <= Protocol.MAX_LINE_BYTES) return line.length();
        return line.getBytes(StandardCharsets.UTF_8).length;
    }

    private void writeLoop() {
        try (OutputStream out = new java.io.BufferedOutputStream(socket.getOutputStream(), 65536)) {
            while (true) {
                byte[] line = outgoing.take();
                if (line == CLOSE) break;
                out.write(line);
                out.write('\n');
                if (outgoing.isEmpty()) out.flush();
            }
            out.flush();
        } catch (IOException e) {
            // 相手が切った
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeSocket();
            close();
        }
    }

    /**
     * 接続を閉じる（冪等）。購読をすべて外し、送信キューに残った応答を書いてからソケットを閉じる。
     */
    void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (Subscription subscription : subscriptions.values()) HandlerList.unregisterAll(subscription.listener);
        subscriptions.clear();
        outgoing.add(CLOSE);
        onClosed.run();
    }

    /** 直ちにソケットを閉じる（エージェントの停止）。 */
    void abort() {
        close();
        closeSocket();
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException e) {
            // 閉じるだけなので無視する
        }
    }
}
