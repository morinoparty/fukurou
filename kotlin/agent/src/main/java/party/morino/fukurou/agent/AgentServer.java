package party.morino.fukurou.agent;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/** TCP で待ち受けて接続を受け付ける（v3 設計 §1.2。複数の接続を受け付ける）。 */
final class AgentServer implements AutoCloseable {
    private final FukurouAgentPlugin plugin;
    private final AgentConfig config;
    private final Logger logger;
    private final MainThread main;
    private final Operations operations;
    private final Set<AgentConnection> connections = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers;
    private volatile ServerSocket serverSocket;

    AgentServer(FukurouAgentPlugin plugin, AgentConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.logger = plugin.getLogger();
        this.main = new MainThread(plugin);
        this.operations = new Operations(plugin, main);
        AtomicInteger counter = new AtomicInteger();
        this.workers = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "FukurouAgent-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** ポートを開いて受け付けを始める。 */
    void start() throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getByName(config.bind()), config.port()), 50);
        serverSocket = socket;
        Thread acceptor = new Thread(this::acceptLoop, "FukurouAgent-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void acceptLoop() {
        ServerSocket socket = serverSocket;
        while (!socket.isClosed()) {
            Socket client;
            try {
                client = socket.accept();
            } catch (SocketException e) {
                // close() で閉じた
                return;
            } catch (IOException e) {
                logger.log(Level.WARNING, "agent accept failed", e);
                continue;
            }
            try {
                client.setTcpNoDelay(true);
            } catch (SocketException e) {
                // 遅延の調整なので失敗しても続ける
            }
            AgentConnection[] holder = new AgentConnection[1];
            AgentConnection connection = new AgentConnection(
                    client, plugin, config.token(), operations, main, workers, () -> connections.remove(holder[0]));
            holder[0] = connection;
            connections.add(connection);
            connection.start();
        }
    }

    /** 待ち受けとすべての接続を閉じる。 */
    @Override
    public void close() {
        ServerSocket socket = serverSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                // 閉じるだけなので無視する
            }
        }
        for (AgentConnection connection : connections) connection.abort();
        connections.clear();
        workers.shutdownNow();
    }
}
