package party.morino.fukurou.log

/** ログの照合の失敗（待ちの時間切れ、出てはいけない行が出た）。プラグインの誤りとしてテストは failed になる。 */
public class LogAssertionError(message: String) : AssertionError(message)
