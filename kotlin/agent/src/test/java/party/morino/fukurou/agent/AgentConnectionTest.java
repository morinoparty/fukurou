package party.morino.fukurou.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * hello の前の振る舞い（v3 設計 §1.2）をループバックのソケットで試す。どれもサーバー（Bukkit）に触れる前に答えて閉じる経路。
 */
@Timeout(30)
class AgentConnectionTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    private ServerSocket listener;
    private Socket client;
    private ExecutorService workers;
    private final CountDownLatch closed = new CountDownLatch(1);

    /** getLogger だけに答える Plugin。 */
    private static Plugin fakePlugin() {
        Logger logger = Logger.getLogger("FukurouAgentTest");
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getLogger")) return logger;
                    if (method.getName().equals("toString")) return "FakePlugin";
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @BeforeEach
    void connect() throws IOException {
        listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
        Socket accepted = listener.accept();
        workers = Executors.newCachedThreadPool();
        // hello が通らない経路では Operations / MainThread に触れないので null でよい
        new AgentConnection(accepted, fakePlugin(), TOKEN, null, null, workers, closed::countDown).start();
    }

    @AfterEach
    void close() throws IOException {
        client.close();
        listener.close();
        workers.shutdownNow();
    }

    private void sendLine(String line) throws IOException {
        OutputStream out = client.getOutputStream();
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** 応答の 1 行を読み、その後に接続が閉じたこと（EOF）を確かめる。 */
    private JsonObject replyThenEof() throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        String line = reader.readLine();
        assertTrue(line != null, "expected a reply line");
        assertNull(reader.readLine(), "the agent must close the connection");
        assertTrue(closed.await(10, TimeUnit.SECONDS), "onClosed must run");
        return JsonParser.parseString(line).getAsJsonObject();
    }

    private static String errorType(JsonObject reply) {
        assertEquals(false, reply.get("ok").getAsBoolean());
        return reply.getAsJsonObject("error").get("type").getAsString();
    }

    @Test
    @DisplayName("a first message other than hello is auth and closes the connection")
    void firstMessageMustBeHello() throws Exception {
        sendLine("{\"id\":1,\"op\":\"ping\"}");
        JsonObject reply = replyThenEof();
        assertEquals(1, reply.get("id").getAsLong());
        assertEquals("auth", errorType(reply));
    }

    @Test
    @DisplayName("a wrong or missing token is auth and closes the connection")
    void wrongToken() throws Exception {
        sendLine("{\"id\":0,\"op\":\"hello\",\"token\":\"nope\",\"protocol\":1}");
        JsonObject reply = replyThenEof();
        assertEquals(0, reply.get("id").getAsLong());
        assertEquals("auth", errorType(reply));
    }

    @Test
    @DisplayName("an unsupported protocol version is bad_request and closes the connection")
    void wrongProtocol() throws Exception {
        sendLine("{\"id\":0,\"op\":\"hello\",\"token\":\"" + TOKEN + "\",\"protocol\":2}");
        assertEquals("bad_request", errorType(replyThenEof()));
    }

    @Test
    @DisplayName("malformed JSON is bad_request with id null")
    void malformed() throws Exception {
        sendLine("{not json");
        JsonObject reply = replyThenEof();
        assertTrue(reply.get("id").isJsonNull());
        assertEquals("bad_request", errorType(reply));
    }

    @Test
    @DisplayName("a line over 16 MiB is bad_request with id null and closes the connection")
    void oversizedLine() throws Exception {
        // ちょうど上限 + 1 バイトを送る（読み残しがあると RST になり応答を読めないことがあるので、それ以上は送らない）
        byte[] payload = new byte[Protocol.MAX_LINE_BYTES + 1];
        Arrays.fill(payload, (byte) 'a');
        Thread writer = new Thread(() -> {
            try {
                OutputStream out = client.getOutputStream();
                out.write(payload);
                out.flush();
            } catch (IOException e) {
                // エージェントが閉じた
            }
        });
        writer.start();
        JsonObject reply = replyThenEof();
        writer.join();
        assertTrue(reply.get("id").isJsonNull());
        assertEquals("bad_request", errorType(reply));
    }
}
