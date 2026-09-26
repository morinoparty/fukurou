package party.morino.fukurou.junit.platform

import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import party.morino.fukurou.junit.GameServerExtension
import party.morino.fukurou.junit.LeaseRegistry

/**
 * 実行の計画（フィルタ済み）から、fukurou の拡張ごとに走るテストを集める（§5.5）。META-INF/services で登録する。
 *
 * 拡張は最初の beforeAll で、ここで集めたテストを「not run」の stub として result.json に書く。フィルタ後の計画なので、
 * stub はちょうど選択されたテストだけになる。テンプレートの n 回目の実行は計画に無いので、実行時に足す。
 *
 * どのテストでも例外を外に出さない（fukurou を使わないテストの実行を妨げない）。
 */
public class FukurouPlanListener : TestExecutionListener {
    /** 計画の全体をたどり、拡張のクラス → テスト（計画順）を LeaseRegistry に渡す。 */
    override fun testPlanExecutionStarted(testPlan: TestPlan) {
        val plan = linkedMapOf<Class<out GameServerExtension>, MutableList<TestIdentity>>()
        val seen = mutableSetOf<String>()
        // 子は発見順（= 既定の実行順）に並んでいるので、深さ優先でたどれば計画順になる
        fun visit(identifier: TestIdentifier) {
            collect(identifier, seen)?.let { (extensions, identity) -> extensions.forEach { plan.getOrPut(it) { mutableListOf() } += identity } }
            testPlan.getChildren(identifier).forEach(::visit)
        }
        runCatching { testPlan.roots.forEach(::visit) }
        LeaseRegistry.plan(plan)
    }

    /** メソッドのテスト（テンプレートのコンテナを含む）なら、登録された拡張と識別を返す。 */
    private fun collect(identifier: TestIdentifier, seen: MutableSet<String>): Pair<List<Class<out GameServerExtension>>, TestIdentity>? =
        runCatching {
            val source = identifier.source.orElse(null) as? MethodSource ?: return null
            val testClass = source.javaClass
            val extensions = FukurouExtensions.find(testClass).ifEmpty { return null }
            val method = source.javaMethod
            // @Test は TEST、@TestTemplate はコンテナとして 1 回ずつ現れる。同じメソッドを 2 回数えない
            if (!seen.add(TestIdentity.key(testClass, method))) return null
            extensions to TestIdentity.of(testClass, method, identifier.tags.map { it.name })
        }.getOrNull()
}
