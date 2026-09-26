package party.morino.fukurou.junit.platform

import org.junit.jupiter.api.DisplayName
import org.junit.platform.commons.support.AnnotationSupport
import party.morino.fukurou.engine.net.Sha256
import party.morino.fukurou.junit.annotation.FreshServer
import party.morino.fukurou.junit.annotation.GameTestId
import party.morino.fukurou.junit.annotation.GameTimeout
import party.morino.fukurou.junit.annotation.MinecraftVersions
import party.morino.fukurou.result.output.ResultIds
import java.lang.reflect.Method

/**
 * JUnit のテストメソッド 1 つが result.json のどのテストになるか（§5.5）。
 *
 * id の重複（同じ result に同じ id）の解消は計画の順序が要るので、ServerLease が ResultIds.dedupeTestIds で行う。
 *
 * @property className テストクラスの FQCN
 * @property simpleClassName テストクラスの単純名（重複した id の接頭辞）
 * @property methodName メソッド名
 * @property key メソッドを一意に指すキー（クラス・名前・引数の型）
 * @property id tests[].id（重複の解消前）
 * @property name tests[].name（@DisplayName か、メソッド名そのもの）
 * @property tags JUnit のタグ（メソッドとクラス）
 * @property source "junit:<fqcn>#<method>"
 * @property sha256 テストクラスの .class の sha256（テストを書き換えると変わる）
 * @property versions @MinecraftVersions の範囲
 * @property timeoutSeconds @GameTimeout の秒数
 * @property fresh @FreshServer が付いているか
 */
internal data class TestIdentity(
    val className: String,
    val simpleClassName: String,
    val methodName: String,
    val key: String,
    val id: String,
    val name: String,
    val tags: List<String>,
    val source: String,
    val sha256: String,
    val versions: String? = null,
    val timeoutSeconds: Long? = null,
    val fresh: Boolean = false,
) {
    /** 作り方と、id の規則。 */
    companion object {
        /** テンプレート（@ParameterizedTest など）の n 回目の実行を示す unique id の断片。 */
        private val INVOCATION = Regex("""\[test-template-invocation:#(\d+)]""")

        /**
         * testClass で実行する method の識別。継承したメソッドは実行するクラス（testClass）の名前で記録する。
         *
         * @param tags JUnit が報告するタグ（メソッドとクラス）
         * @throws IllegalArgumentException @GameTestId が id の規則に合わない
         */
        fun of(testClass: Class<*>, method: Method, tags: Collection<String>): TestIdentity {
            val gameTestId = AnnotationSupport.findAnnotation(method, GameTestId::class.java).orElse(null)?.value
            val displayName = AnnotationSupport.findAnnotation(method, DisplayName::class.java).orElse(null)?.value
            val source = source(testClass.name, method.name)
            return TestIdentity(
                className = testClass.name,
                simpleClassName = testClass.simpleName,
                methodName = method.name,
                key = key(testClass, method),
                id = id(method.name, gameTestId),
                name = name(method.name, displayName),
                tags = tags.toSortedSet().toList(),
                source = source,
                sha256 = classSha256(testClass) ?: Sha256.of(source),
                versions = annotationOf(testClass, method, MinecraftVersions::class.java)?.spec,
                timeoutSeconds = annotationOf(testClass, method, GameTimeout::class.java)?.seconds,
                fresh = AnnotationSupport.isAnnotated(method, FreshServer::class.java),
            )
        }

        /** メソッドを一意に指すキー（オーバーロードを区別するため引数の型を含める）。 */
        fun key(testClass: Class<*>, method: Method): String =
            "${testClass.name}#${method.name}(${method.parameterTypes.joinToString(",") { it.name }})"

        /**
         * tests[].id。@GameTestId があれば検査してそのまま、無ければメソッド名を整える（`stamp thinking face` → stamp-thinking-face）。
         *
         * @throws IllegalArgumentException @GameTestId が規則に合わない
         */
        fun id(methodName: String, gameTestId: String?): String =
            gameTestId?.let(ResultIds::checkTestId) ?: ResultIds.sanitizeTestId(methodName)

        /** tests[].name。context.displayName は引数の一覧を含むので使わない。 */
        fun name(methodName: String, displayName: String?): String = displayName ?: methodName

        /** tests[].source。 */
        fun source(className: String, methodName: String): String = "junit:$className#$methodName"

        /** unique id からテンプレートの実行番号（1 始まり）を読む。テンプレートの実行でなければ null。 */
        fun invocation(uniqueId: String): Int? = INVOCATION.findAll(uniqueId).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()

        /** テンプレートの n 回目の実行の id。 */
        fun invocationId(id: String, invocation: Int?): String = if (invocation == null) id else ResultIds.invocationId(id, invocation)

        /** クラスローダーから読んだ .class の sha256。読めなければ null。 */
        fun classSha256(kind: Class<*>): String? {
            val resource = kind.name.replace('.', '/') + ".class"
            val bytes = runCatching { (kind.classLoader ?: ClassLoader.getSystemClassLoader()).getResourceAsStream(resource)?.use { it.readBytes() } }
                .getOrNull() ?: return null
            return Sha256.of(bytes)
        }

        /** メソッド、無ければクラス（外側を含む）の注釈。 */
        private fun <A : Annotation> annotationOf(testClass: Class<*>, method: Method, kind: Class<A>): A? =
            AnnotationSupport.findAnnotation(method, kind).orElse(null)
                ?: FukurouExtensions.classChain(testClass).firstNotNullOfOrNull { AnnotationSupport.findAnnotation(it, kind).orElse(null) }
    }
}
