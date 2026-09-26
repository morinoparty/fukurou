package party.morino.fukurou.result.output

import party.morino.fukurou.result.model.suite.CiInfo

/** GitHub Actions の環境変数から、実行元のリポジトリやコミットの情報を読む（result/ci.py）。 */
internal object CiEnvironment {
    /**
     * GitHub Actions 上でなければ null を返す（ローカル実行では ci を null にする契約）。
     *
     * @param env 環境変数（テストでは任意の Map を渡す）
     */
    fun fromEnv(env: Map<String, String>): CiInfo? {
        // GITHUB_ACTIONS=true だけを CI とみなす。値が無い・別の値ならローカル実行
        if (env["GITHUB_ACTIONS"] != "true") return null
        return CiInfo(
            repository = env["GITHUB_REPOSITORY"],
            sha = env["GITHUB_SHA"],
            ref = env["GITHUB_REF"],
            runId = env["GITHUB_RUN_ID"],
            runAttempt = env["GITHUB_RUN_ATTEMPT"],
            serverUrl = env["GITHUB_SERVER_URL"],
        )
    }
}
