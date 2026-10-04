package party.morino.fukurou.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * fukurou のサーバー内エージェント（v3 設計 §1）。
 *
 * <p>有効化のときに plugins/FukurouAgent/agent.properties（port・token・bind）を読み、ループバックの TCP で待ち受ける。
 * ファイルが無ければ警告を出して何もしない（fukurou 以外が入れた場合）。
 */
public final class FukurouAgentPlugin extends JavaPlugin {
    private AgentServer server;

    @Override
    public void onEnable() {
        Path file = getDataFolder().toPath().resolve(AgentConfig.FILE_NAME);
        if (!Files.isRegularFile(file)) {
            getLogger().warning(file + " not found; the agent stays idle");
            return;
        }
        AgentConfig config;
        try {
            config = AgentConfig.load(file);
        } catch (IOException | IllegalArgumentException e) {
            getLogger().log(Level.SEVERE, "cannot read " + file + "; the agent stays idle", e);
            return;
        }
        AgentServer started = new AgentServer(this, config);
        try {
            started.start();
        } catch (IOException e) {
            started.close();
            getLogger().log(Level.SEVERE, "cannot listen on " + config.bind() + ":" + config.port() + "; the agent stays idle", e);
            return;
        }
        server = started;
        getLogger().info("listening on " + config.bind() + ":" + config.port());
    }

    @Override
    public void onDisable() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    /** hello で返すエージェントの版（plugin.yml の version）。 */
    String agentVersion() {
        return Operations.pluginVersion(this);
    }
}
