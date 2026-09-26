package party.morino.fukurou.result.output

import party.morino.fukurou.error.ClientDiedException
import party.morino.fukurou.error.FukurouException
import party.morino.fukurou.error.HarnessTimeoutException
import party.morino.fukurou.error.InputException
import party.morino.fukurou.error.ServerUnavailableException
import party.morino.fukurou.result.StepEvent
import party.morino.fukurou.result.event.TestOutcome
import party.morino.fukurou.result.model.enums.RunFailurePhase
import party.morino.fukurou.result.model.enums.TestFailurePhase
import party.morino.fukurou.result.model.enums.TestStatus
import party.morino.fukurou.result.model.run.RunFailure
import party.morino.fukurou.result.model.step.StepPhase
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/**
 * テストが投げた例外を result.json の status / failure.phase に対応づける（§6.4、run/step_executor.py の分類）。
 *
 * 純粋関数。JUnit が無くても動くよう、opentest4j の TestAbortedException はクラス名で見分ける。
 */
internal object StatusMapper {
    /** JUnit の仮定の失敗（Assumptions）。compileOnly のため、クラスを直接参照しない。 */
    private const val TEST_ABORTED = "org.opentest4j.TestAbortedException"

    /** ステップの失敗を表す段階（model.py STEP_FAILURE_PHASES）。これ以外の段階はハーネス側の失敗（error）。 */
    val STEP_FAILURE_PHASES: Set<TestFailurePhase> =
        setOf(TestFailurePhase.BEFORE_EACH, TestFailurePhase.FIXTURE, TestFailurePhase.SCENARIO)

    /**
     * テスト 1 件の結末。
     *
     * @param error テストが投げた例外（null なら成功）
     * @param failingStep 失敗したステップ（ステップに結び付かない失敗なら null）
     * @param leasePhase 失敗したときにテストが居た層（beforeEach / test）。失敗したステップが無いときに使う
     * @param clientDead 参加者のクライアントが既に死んでいるか（入力の失敗をクライアントの死亡として扱う）
     * @param resetError テスト前のリセットの失敗
     */
    fun outcome(
        error: Throwable?,
        failingStep: StepEvent? = null,
        leasePhase: StepPhase = StepPhase.TEST,
        clientDead: Boolean = false,
        resetError: String? = null,
    ): TestOutcome {
        val stepId = failingStep?.provisionalId
        // リセットの失敗はテストの本体より前に起きたハーネス側の失敗
        if (resetError != null) return TestOutcome(TestStatus.ERROR, TestFailurePhase.RESET, resetError)
        val cause = error?.let(::unwrap) ?: return TestOutcome(TestStatus.PASSED)
        val message = messageOf(cause)
        return when {
            // 仮定の失敗・死んだサーバー・再起動の失敗は「走らなかった」
            isAborted(cause) -> TestOutcome(TestStatus.SKIPPED, skipReason = message.ifEmpty { "aborted" })
            // クライアントの死亡、または死んだクライアントへの入力の失敗は、プラグインではなくハーネス側の失敗
            cause is ClientDiedException || (cause is InputException && clientDead) ->
                TestOutcome(TestStatus.ERROR, TestFailurePhase.CLIENT, message, stepId)
            // ソフトデッドラインと JUnit の timeout（バックストップ）はどちらも期限切れ
            cause is HarnessTimeoutException || cause is TimeoutException ->
                TestOutcome(TestStatus.ERROR, TestFailurePhase.TIMEOUT, message, stepId)
            // サーバーの死亡を見たステップは failed。run の失敗は runFailure で別に記録する
            cause is ServerUnavailableException -> TestOutcome(TestStatus.FAILED, TestFailurePhase.SCENARIO, message, stepId)
            // ログの照合・コマンドの失敗・opentest4j の assert はプラグインの誤りの候補
            cause is AssertionError -> TestOutcome(TestStatus.FAILED, phaseOf(failingStep?.phase ?: leasePhase), message, stepId)
            // それ以外（テストのコードの例外など）はハーネスから見て予期しない失敗
            else -> TestOutcome(TestStatus.ERROR, TestFailurePhase.SCENARIO, message, stepId)
        }
    }

    /**
     * run 全体の失敗。サーバーの死亡だけが run を止める（以降のテストは skipped）。
     *
     * @param error テストが投げた例外
     * @param testId 死亡を見たテストの id
     */
    fun runFailure(error: Throwable?, testId: String): RunFailure? {
        val cause = error?.let(::unwrap) ?: return null
        if (cause !is ServerUnavailableException) return null
        return RunFailure(RunFailurePhase.SERVER, "${serverDiedReason(testId)}: ${messageOf(cause)}")
    }

    /** サーバーが死んだ後のテストの skipReason。 */
    fun serverDiedReason(testId: String): String = "server died during $testId"

    /** ステップの層を失敗の段階にする（test → scenario）。 */
    fun phaseOf(phase: StepPhase): TestFailurePhase =
        when (phase) {
            StepPhase.BEFORE_EACH -> TestFailurePhase.BEFORE_EACH
            StepPhase.FIXTURE -> TestFailurePhase.FIXTURE
            StepPhase.TEST -> TestFailurePhase.SCENARIO
        }

    /**
     * 例外のメッセージ。fukurou の例外と assert はそのまま、それ以外は型名を付ける（step_executor.py:96）。
     */
    fun messageOf(error: Throwable): String =
        if (error is FukurouException || error is AssertionError || isAborted(error)) {
            error.message.orEmpty()
        } else {
            // 予期しない例外は型名が無いと原因を追えない
            "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        }

    /** リフレクションや Future の包みを外し、元の例外を取り出す。 */
    private fun unwrap(error: Throwable): Throwable {
        var current = error
        // 包みの中身が無い場合や循環に備えて、段数を区切る
        repeat(8) {
            val inner = (current as? InvocationTargetException)?.targetException ?: (current as? ExecutionException)?.cause
            current = inner ?: return current
        }
        return current
    }

    /** opentest4j の TestAbortedException（またはその派生）か。クラスを読み込まずに親クラスの名前で調べる。 */
    private fun isAborted(error: Throwable): Boolean =
        generateSequence<Class<*>>(error.javaClass) { it.superclass }.any { it.name == TEST_ABORTED }
}
