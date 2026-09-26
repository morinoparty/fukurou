package party.morino.fukurou.error

/** fukurou 自身（ハーネス）の失敗の基底。テストでは error として記録される（AssertionError は failed）。 */
public open class FukurouException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
