package party.morino.fukurou.junit.annotation

import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.platform.commons.support.AnnotationSupport
import party.morino.fukurou.junit.GameServerExtension
import party.morino.fukurou.junit.LeaseRegistry
import party.morino.fukurou.junit.platform.FukurouExtensions
import party.morino.fukurou.server.ServerType
import party.morino.fukurou.version.VersionSpec

/**
 * @MinecraftVersions の範囲の外のバージョンのサーバーでは、テストを無効にする ExecutionCondition。
 *
 * メソッド単位でだけ判定する（クラスのコンテナは有効のまま、クラスの指定もメソッドごとに読む）。こうすると
 * 無効にしたテストも TestWatcher.testDisabled を通って result.json に skipped として残る。
 * バージョンは fukurou の拡張の type(config) から、何も起動せずに求める。
 */
public class MinecraftVersionCondition : ExecutionCondition {
    /** テストメソッドが、登録されたすべての fukurou のサーバーのバージョンで走るか。 */
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        val method = context.testMethod.orElse(null) ?: return ConditionEvaluationResult.enabled("fukurou checks @MinecraftVersions per test method")
        val testClass = context.requiredTestClass
        // メソッドの指定が優先。無ければクラス（@Nested の外側を含む）
        val annotation = AnnotationSupport.findAnnotation(method, MinecraftVersions::class.java).orElse(null)
            ?: FukurouExtensions.classChain(testClass).firstNotNullOfOrNull { AnnotationSupport.findAnnotation(it, MinecraftVersions::class.java).orElse(null) }
            ?: return ConditionEvaluationResult.enabled("no @MinecraftVersions")
        val spec = VersionSpec.parse(annotation.spec)
        for (extension in FukurouExtensions.find(testClass)) {
            val version = typeOf(extension)?.minecraftVersion ?: continue
            // Python の versions: と同じ理由の文言にする（ビューアや PR のコメントで同じに見える）
            if (!spec.contains(version)) return ConditionEvaluationResult.disabled("versions: ${annotation.spec} does not include ${version.id}")
        }
        return ConditionEvaluationResult.enabled("versions: ${annotation.spec}")
    }

    /** 拡張のサーバーの種類。リースがあればその定義、無ければ拡張を作って type(config) を呼ぶ。 */
    private fun typeOf(extension: Class<out GameServerExtension>): ServerType? {
        LeaseRegistry.lease(extension)?.let { return it.definition.type }
        val instance = FukurouExtensions.instantiate(extension) ?: return null
        return instance.serverType(instance.fukurou().config)
    }
}
