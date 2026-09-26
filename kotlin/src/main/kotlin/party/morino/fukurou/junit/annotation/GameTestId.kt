package party.morino.fukurou.junit.annotation

/**
 * result.json の tests[].id を明示する（既定はメソッド名を整えたもの）。^[A-Za-z0-9][A-Za-z0-9_.-]*$ に合うこと。
 *
 * @property value テストの id
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class GameTestId(val value: String)
