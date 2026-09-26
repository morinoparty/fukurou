package party.morino.fukurou.result.model.run

import kotlinx.serialization.Serializable

/**
 * 結果を書いた fukurou の情報。
 *
 * @property version fukurou のバージョン
 * @property portablemc 使った PortableMC のバージョン
 * @property runner 結果を書いたランナー（新しい任意項目。Kotlin 版は "kotlin"）
 */
@Serializable
public data class FukurouInfo(
    val version: String,
    val portablemc: String,
    val runner: String? = "kotlin",
)
