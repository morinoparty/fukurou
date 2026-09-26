package party.morino.fukurou.result.model.run

import kotlinx.serialization.Serializable

/**
 * テストのステータスごとの件数（model.py summarize_tests）。
 *
 * @property total 全件
 * @property passed passed の件数
 * @property failed failed の件数
 * @property error error の件数
 * @property skipped skipped の件数
 */
@Serializable
public data class Summary(
    val total: Int = 0,
    val passed: Int = 0,
    val failed: Int = 0,
    val error: Int = 0,
    val skipped: Int = 0,
)
