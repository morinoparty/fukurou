package party.morino.fukurou.agent;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * plugins/FukurouAgent/agent.properties の中身（v3 設計 §1.1）。fukurou の PaperPlatform が書く。
 *
 * @param port 待ち受けるポート
 * @param token hello で照合するトークン
 * @param bind 待ち受けるアドレス（既定 127.0.0.1）
 */
record AgentConfig(int port, String token, String bind) {
    /** 設定ファイルの名前。 */
    static final String FILE_NAME = "agent.properties";

    /** ファイルから読む。 */
    static AgentConfig load(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return parse(properties);
    }

    /**
     * プロパティから作る。
     *
     * @throws IllegalArgumentException port が 1〜65535 の整数でない、token が無いとき
     */
    static AgentConfig parse(Properties properties) {
        String portText = properties.getProperty("port");
        if (portText == null || portText.isBlank()) throw new IllegalArgumentException("port is missing");
        int port;
        try {
            port = Integer.parseInt(portText.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("port is not an integer: " + portText);
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be in 1..65535, got " + port);
        String token = properties.getProperty("token");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("token is missing");
        String bind = properties.getProperty("bind");
        if (bind == null || bind.isBlank()) bind = "127.0.0.1";
        return new AgentConfig(port, token.trim(), bind.trim());
    }

    @Override
    public String toString() {
        // トークンはログに出さない
        return "AgentConfig[bind=" + bind + ", port=" + port + "]";
    }
}
