package party.morino.fukurou.error

/** 準備の失敗（EULA 未同意、ホストの道具の不足、設定の誤り、ダウンロードの失敗など）。 */
public class SetupException(message: String, cause: Throwable? = null) : FukurouException(message, cause)
