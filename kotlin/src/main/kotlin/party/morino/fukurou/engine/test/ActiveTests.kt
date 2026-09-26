package party.morino.fukurou.engine.test

import java.util.concurrent.CopyOnWriteArrayList

/**
 * いま実行中のテスト（JVM 全体）。
 *
 * StepScope を持たない呼び出し（JUnit のテスト本体から呼ばれた pause() など）は、ここから記録先のテストを探す（§1.8）。
 * この暗黙の探索が成り立つのはテストを 1 件ずつ実行する場合だけなので、別のテストが走っている間の開始は拒む（§5.6）。
 * 同じテスト（owner）を複数のサーバーで同時に実行するのは許す（1 つのテストクラスに拡張が 2 つある場合）。
 */
internal object ActiveTests {
    /** 実行中のテストと、その持ち主（同じテストかどうかの判定に使う）。 */
    private val runs = CopyOnWriteArrayList<Pair<TestRun, Any>>()

    /**
     * テストの開始を登録する。
     *
     * @param run 始めるテスト
     * @param owner テストの持ち主。同じ owner のテストは同時に走ってよい（JUnit のテスト 1 件を 2 台のサーバーで実行する場合）
     * @throws IllegalStateException 別の owner のテストが実行中
     */
    @Synchronized
    fun begin(run: TestRun, owner: Any = run) {
        // 並列に走るテストがあると pause() や Audience の呼び出しがどのテストに記録すべきか決まらない
        val other = runs.firstOrNull { it.second != owner }
        check(other == null) {
            "test ${run.testId} cannot start while ${other?.first?.testId} is running on ${other?.first?.host?.resultId}; " +
                "fukurou runs one test at a time (junit.jupiter.execution.parallel.enabled=false)"
        }
        runs += run to owner
    }

    /** テストの終了。登録されていなければ何もしない。 */
    fun finish(run: TestRun) {
        runs.removeIf { it.first === run }
    }

    /** 実行中のテスト（開始順）。 */
    fun all(): List<TestRun> = runs.map { it.first }

    /** host のサーバーで実行中のテスト。 */
    fun on(host: StepHost): TestRun? = runs.firstOrNull { it.first.host === host }?.first
}
