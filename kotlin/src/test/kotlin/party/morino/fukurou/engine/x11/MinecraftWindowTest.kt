package party.morino.fukurou.engine.x11

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class MinecraftWindowTest {
    @Test
    @DisplayName("Single-digit keycodes are sent as keysym names so xdotool does not read them as digits")
    fun keyToken() {
        // Escape は evdev のキーコード 9。"9" を渡すと数字キーの 9 が押されてしまう
        assertEquals("Escape", MinecraftWindow.keyToken("Escape", 9))
        // 2 桁以上のキーコードは keysym 名として読めないので、そのままキーコードとして渡す
        assertEquals("71", MinecraftWindow.keyToken("F5", 71))
    }
}
