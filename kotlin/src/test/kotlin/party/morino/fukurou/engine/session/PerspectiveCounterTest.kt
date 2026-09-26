package party.morino.fukurou.engine.session

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.fukurou.player.Perspective
import org.junit.jupiter.api.Assertions.assertEquals

class PerspectiveCounterTest {
    @Test
    @DisplayName("F5 presses cycle the perspective modulo three")
    fun presses() {
        val counter = PerspectiveCounter()
        assertEquals(0, counter.pressesTo(Perspective.FIRST_PERSON))
        assertEquals(2, counter.pressesTo(Perspective.THIRD_PERSON_FRONT))
        counter.pressed()
        assertEquals(Perspective.THIRD_PERSON_BACK, counter.current)
        assertEquals(2, counter.pressesToFirstPerson())
        assertEquals(2, counter.pressesTo(Perspective.FIRST_PERSON))
        repeat(3) { counter.pressed() }
        assertEquals(2, counter.pressesToFirstPerson())
        counter.reset()
        assertEquals(0, counter.pressesToFirstPerson())
    }
}
