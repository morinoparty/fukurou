package party.morino.fukurou.result.model.suite

import kotlinx.serialization.Serializable

/**
 * GitHub Actions 上で実行したときだけ埋める（result/ci.py）。
 *
 * @property repository GITHUB_REPOSITORY
 * @property sha GITHUB_SHA
 * @property ref GITHUB_REF
 * @property runId GITHUB_RUN_ID
 * @property runAttempt GITHUB_RUN_ATTEMPT
 * @property serverUrl GITHUB_SERVER_URL
 */
@Serializable
public data class CiInfo(
    val repository: String? = null,
    val sha: String? = null,
    val ref: String? = null,
    val runId: String? = null,
    val runAttempt: String? = null,
    val serverUrl: String? = null,
)
