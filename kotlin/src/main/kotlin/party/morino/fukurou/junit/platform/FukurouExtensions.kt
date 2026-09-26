package party.morino.fukurou.junit.platform

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.platform.commons.annotation.Testable
import org.junit.platform.commons.support.AnnotationSupport
import org.junit.platform.commons.support.HierarchyTraversalMode
import party.morino.fukurou.junit.GameServerExtension
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * テストクラスに登録された fukurou の拡張を、JUnit を動かさずに注釈から探す。
 *
 * 計画のリスナー・クラスの並べ替え・@MinecraftVersions の判定・コンストラクタ注入の案内が同じ規則で探すよう、ここに集める。
 */
internal object FukurouExtensions {
    /**
     * testClass に登録された fukurou の拡張のクラス（外側のクラスのものが先、重複なし）。
     *
     * @ExtendWith（メタ注釈と継承を含む）と、型が GameServerExtension の @RegisterExtension フィールドを見る。
     * @Nested（内部クラス）は外側のクラスの拡張を引き継ぐので、外側もたどる。
     */
    fun find(testClass: Class<*>): List<Class<out GameServerExtension>> {
        val found = linkedSetOf<Class<out GameServerExtension>>()
        // JUnit と同じく外側のクラスの登録を先にする
        classChain(testClass).asReversed().forEach { current ->
            AnnotationSupport.findRepeatableAnnotations(current, ExtendWith::class.java)
                .flatMap { it.value.map { kind -> kind.java } }
                .forEach { kind -> asFukurou(kind)?.let(found::add) }
            AnnotationSupport.findAnnotatedFields(current, RegisterExtension::class.java)
                .forEach { field -> fieldExtension(field)?.let(found::add) }
        }
        return found.toList()
    }

    /**
     * testClass と、@Nested として引き継ぐ外側のクラス（内側から順に）。
     */
    fun classChain(testClass: Class<*>): List<Class<*>> =
        // static な入れ子クラスは外側の拡張を引き継がないので、内部クラスの間だけたどる
        generateSequence(testClass) { current -> current.enclosingClass?.takeIf { !Modifier.isStatic(current.modifiers) } }.toList()

    /**
     * 拡張を引数なしのコンストラクタで作る（まだリースが無いときの版の判定や run id の計算用）。作れなければ null。
     */
    fun instantiate(kind: Class<out GameServerExtension>): GameServerExtension? =
        runCatching { kind.getDeclaredConstructor().apply { isAccessible = true }.newInstance() }.getOrNull()

    /**
     * testClass の @Test / @TestTemplate などのテストメソッド（計画のリスナーが無いときの代わり）。
     */
    fun testMethods(testClass: Class<*>): List<Method> =
        // @Test も @ParameterizedTest も @Testable をメタ注釈に持つ
        AnnotationSupport.findAnnotatedMethods(testClass, Testable::class.java, HierarchyTraversalMode.TOP_DOWN)

    /**
     * メソッドとそのクラス（外側を含む）の @Tag（リスナーが無いときの代わり）。
     */
    fun tags(testClass: Class<*>, method: Method): Set<String> =
        (classChain(testClass).flatMap { AnnotationSupport.findRepeatableAnnotations(it, Tag::class.java) } +
            AnnotationSupport.findRepeatableAnnotations(method, Tag::class.java)).map { it.value }.toSortedSet()

    /** fukurou の拡張なら型を狭めて返す。 */
    @Suppress("UNCHECKED_CAST")
    private fun asFukurou(kind: Class<*>): Class<out GameServerExtension>? =
        kind.takeIf { GameServerExtension::class.java.isAssignableFrom(it) && !Modifier.isAbstract(it.modifiers) } as Class<out GameServerExtension>?

    /**
     * @RegisterExtension のフィールドの拡張の型。宣言が抽象型なら static の値から実際の型を読む。
     *
     * static でないフィールドの拡張は、JUnit がテストのインスタンスを作った後に登録するので beforeAll / afterAll が呼ばれず、
     * サーバーを起動できない。計画（stub の result.json）にも載せない。
     */
    private fun fieldExtension(field: java.lang.reflect.Field): Class<out GameServerExtension>? {
        if (!Modifier.isStatic(field.modifiers)) return null
        asFukurou(field.type)?.let { return it }
        if (!GameServerExtension::class.java.isAssignableFrom(field.type)) return null
        return runCatching { field.isAccessible = true; field.get(null)?.javaClass?.let(::asFukurou) }.getOrNull()
    }
}
