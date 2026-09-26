package party.morino.fukurou.junit.annotation

/**
 * このテストの前にサーバーを作り直す（新しい fresh-server のセッションで全員を参加させ直す）。
 *
 * 起動直後でまだテストを走らせていないセッションは既に新品なので、作り直さない。
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class FreshServer
