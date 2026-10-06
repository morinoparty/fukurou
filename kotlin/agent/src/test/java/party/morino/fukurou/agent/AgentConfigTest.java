package party.morino.fukurou.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** agent.properties の読み取り（v3 設計 §1.1）。 */
class AgentConfigTest {
    private static Properties properties(String... pairs) {
        Properties properties = new Properties();
        for (int i = 0; i < pairs.length; i += 2) properties.setProperty(pairs[i], pairs[i + 1]);
        return properties;
    }

    @Test
    @DisplayName("reads port, token and bind with 127.0.0.1 as the default bind")
    void reads() {
        AgentConfig config = AgentConfig.parse(properties("port", "25599", "token", "abc"));
        assertEquals(25599, config.port());
        assertEquals("abc", config.token());
        assertEquals("127.0.0.1", config.bind());
        assertEquals("0.0.0.0", AgentConfig.parse(properties("port", "1", "token", "t", "bind", "0.0.0.0")).bind());
    }

    @Test
    @DisplayName("rejects a missing or invalid port and a missing token")
    void rejects() {
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse(properties("token", "t")));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse(properties("port", "x", "token", "t")));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse(properties("port", "70000", "token", "t")));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse(properties("port", "1")));
    }

    @Test
    @DisplayName("toString does not leak the token")
    void hidesToken() {
        assertFalse(AgentConfig.parse(properties("port", "1", "token", "secret-token")).toString().contains("secret-token"));
    }
}
