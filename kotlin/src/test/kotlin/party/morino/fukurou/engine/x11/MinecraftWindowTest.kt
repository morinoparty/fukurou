package party.morino.fukurou.engine.x11

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import party.morino.fukurou.player.MouseButton

class MinecraftWindowTest {
    @Test
    @DisplayName("Single-digit keycodes are sent as keysym names so xdotool does not read them as digits")
    fun keyToken() {
        // Escape は evdev のキーコード 9。"9" を渡すと数字キーの 9 が押されてしまう
        assertEquals("Escape", MinecraftWindow.keyToken("Escape", 9))
        // 2 桁以上のキーコードは keysym 名として読めないので、そのままキーコードとして渡す
        assertEquals("71", MinecraftWindow.keyToken("F5", 71))
    }

    @Test
    @DisplayName("Held keys use keydown/keyup without --clearmodifiers so a held Shift stays held")
    fun keyDownAndUp() {
        assertEquals(listOf("keydown", "25"), MinecraftWindow.keyDownArgs("25").toList())
        assertEquals(listOf("keyup", "50"), MinecraftWindow.keyUpArgs("50").toList())
    }

    @Test
    @DisplayName("Mouse buttons map to xdotool button numbers without --window")
    fun buttons() {
        assertEquals(listOf("click", "1"), MinecraftWindow.clickArgs(MouseButton.LEFT).toList())
        assertEquals(listOf("click", "2"), MinecraftWindow.clickArgs(MouseButton.MIDDLE).toList())
        assertEquals(listOf("mousedown", "3"), MinecraftWindow.mouseDownArgs(MouseButton.RIGHT).toList())
        assertEquals(listOf("mouseup", "3"), MinecraftWindow.mouseUpArgs(MouseButton.RIGHT).toList())
    }

    @Test
    @DisplayName("Mouse moves are window-relative and limited to the 1280x720 window")
    fun mouseMove() {
        assertEquals(
            listOf("mousemove", "--window", "4194307", "640", "360"),
            MinecraftWindow.mouseMoveArgs(4194307L, 640, 360).toList(),
        )
        // 端はウィンドウの中
        MinecraftWindow.mouseMoveArgs(1L, 0, 0)
        MinecraftWindow.mouseMoveArgs(1L, 1279, 719)
        for ((x, y) in listOf(-1 to 0, 0 to -1, 1280 to 0, 0 to 720)) {
            val error = assertThrows<IllegalArgumentException> { MinecraftWindow.checkPoint(x, y) }
            assertEquals(
                "($x, $y) is outside the 1280x720 Minecraft window (x must be 0..1279, y must be 0..719)",
                error.message,
            )
        }
    }

    @Test
    @DisplayName("Scrolling down clicks button 5 and scrolling up clicks button 4, repeated per step")
    fun scroll() {
        assertEquals(listOf("click", "--repeat", "3", "--delay", "50", "5"), MinecraftWindow.scrollArgs(3).toList())
        assertEquals(listOf("click", "--repeat", "2", "--delay", "50", "4"), MinecraftWindow.scrollArgs(-2).toList())
        assertEquals("2147483648", MinecraftWindow.scrollArgs(Int.MIN_VALUE)[2])
        assertThrows<IllegalArgumentException> { MinecraftWindow.scrollArgs(0) }
    }

    @Test
    @DisplayName("Long scrolls are split into chunks that each finish well within the xdotool timeout")
    fun scrollChunks() {
        assertEquals(listOf(3), MinecraftWindow.scrollChunks(3).toList())
        assertEquals(listOf(-2), MinecraftWindow.scrollChunks(-2).toList())
        assertEquals(listOf(100, 100, 50), MinecraftWindow.scrollChunks(250).toList())
        assertEquals(listOf(-100, -1), MinecraftWindow.scrollChunks(-101).toList())
        // Int.MIN_VALUE でも溢れず、最後の塊は残りの段数になる
        assertEquals(listOf(-100, -100), MinecraftWindow.scrollChunks(Int.MIN_VALUE).take(2).toList())
        assertEquals(-48, MinecraftWindow.scrollChunks(Int.MIN_VALUE).last())
        assertEquals(21_474_837, MinecraftWindow.scrollChunks(Int.MIN_VALUE).count())
        assertThrows<IllegalArgumentException> { MinecraftWindow.scrollChunks(0) }
    }
}
