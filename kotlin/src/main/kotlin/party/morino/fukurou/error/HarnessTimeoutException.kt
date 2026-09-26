package party.morino.fukurou.error

/** テストの期限（ソフトデッドライン）やハーネスの待ちが時間切れになった。テストは error（phase timeout）になる。 */
public class HarnessTimeoutException(message: String) : FukurouException(message)
