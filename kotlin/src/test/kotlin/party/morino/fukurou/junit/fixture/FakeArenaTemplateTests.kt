package party.morino.fukurou.junit.fixture

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/** テンプレートと、始めた後に中断されるテスト。ランチャーから実行する。 */
@ExtendWith(FakeArena::class)
class FakeArenaTemplateTests {
    @RepeatedTest(2)
    fun repeated() {}

    @Test
    fun `is aborted by an assumption`() {
        assumeTrue(false, "not on this host")
    }
}
