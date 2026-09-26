package party.morino.fukurou.junit.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import party.morino.fukurou.junit.annotation.FreshServer
import party.morino.fukurou.junit.annotation.GameTestId
import party.morino.fukurou.junit.annotation.GameTimeout
import party.morino.fukurou.junit.annotation.MinecraftVersions

class TestIdentityTest {
    /** 識別を作る対象のテストクラス（実行はしない）。 */
    @GameTimeout(120)
    @MinecraftVersions("1.21.6-")
    class Sample {
        fun `stamp thinking face`() {}

        @GameTestId("custom.id")
        @DisplayName("Shows the stamp")
        @FreshServer
        @GameTimeout(30)
        fun annotated() {}

        @GameTestId("-bad")
        fun badId() {}
    }

    /** Sample のメソッド。 */
    private fun method(name: String) = Sample::class.java.getDeclaredMethod(name)

    @Test
    @DisplayName("turns a backtick name into a safe id and keeps the bare name")
    fun backtick() {
        val identity = TestIdentity.of(Sample::class.java, method("stamp thinking face"), listOf("slow", "chat"))
        assertEquals("stamp-thinking-face", identity.id)
        assertEquals("stamp thinking face", identity.name)
        assertEquals("junit:${Sample::class.java.name}#stamp thinking face", identity.source)
        assertEquals(listOf("chat", "slow"), identity.tags)
        assertEquals(64, identity.sha256.length)
        // クラスの指定が引き継がれる
        assertEquals(120L, identity.timeoutSeconds)
        assertEquals("1.21.6-", identity.versions)
    }

    @Test
    @DisplayName("prefers @GameTestId, @DisplayName and method-level annotations")
    fun annotations() {
        val identity = TestIdentity.of(Sample::class.java, method("annotated"), emptyList())
        assertEquals("custom.id", identity.id)
        assertEquals("Shows the stamp", identity.name)
        assertEquals(30L, identity.timeoutSeconds)
        assertTrue(identity.fresh)
        assertThrows<IllegalArgumentException> { TestIdentity.of(Sample::class.java, method("badId"), emptyList()) }
    }

    @Test
    @DisplayName("adds the invocation number of a template run")
    fun invocation() {
        val uniqueId = "[engine:junit-jupiter]/[class:a.B]/[test-template:c(int)]/[test-template-invocation:#3]"
        assertEquals(3, TestIdentity.invocation(uniqueId))
        assertEquals("c-3", TestIdentity.invocationId("c", TestIdentity.invocation(uniqueId)))
        assertNull(TestIdentity.invocation("[engine:junit-jupiter]/[class:a.B]/[method:c()]"))
        assertEquals("c", TestIdentity.invocationId("c", null))
    }
}
