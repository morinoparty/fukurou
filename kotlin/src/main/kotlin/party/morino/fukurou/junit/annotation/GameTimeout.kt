package party.morino.fukurou.junit.annotation

/**
 * テスト 1 件の期限（ソフトデッドライン）を秒で上書きする。メソッドの指定がクラスの指定より優先する。
 *
 * 既定は ServerSpec.testTimeout（600 秒）。JUnit の timeout は期限の外側の保険なので、それより短くすること。
 *
 * @property seconds 期限（秒）
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class GameTimeout(val seconds: Long)
