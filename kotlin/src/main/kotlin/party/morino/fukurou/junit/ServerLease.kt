package party.morino.fukurou.junit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import party.morino.fukurou.Fukurou
import party.morino.fukurou.engine.session.ServerInstance
import party.morino.fukurou.engine.step.StepScope
import party.morino.fukurou.engine.test.TestRun
import party.morino.fukurou.error.FukurouException
import party.morino.fukurou.junit.platform.FukurouExtensions
import party.morino.fukurou.junit.platform.TestIdentity
import party.morino.fukurou.player.PlayerProfile
import party.morino.fukurou.result.event.PlannedTest
import party.morino.fukurou.result.model.enums.RunFailurePhase
import party.morino.fukurou.result.model.enums.SessionKind
import party.morino.fukurou.result.model.kind.IsolationMode
import party.morino.fukurou.result.output.ResultIds
import party.morino.fukurou.result.output.StatusMapper
import party.morino.fukurou.server.Isolation
import party.morino.fukurou.server.ServerDefinition
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 拡張のクラス 1 つ分のサーバー（1 台のサーバー = 1 つの result.json）。JUnit のルートストアに置く（§5.2）。
 *
 * JUnit は拡張のインスタンスをテストクラスごとに作りうるので、状態はすべてここに置き、同じ拡張を使うすべての
 * テストクラスで共有する。エンジンの実行の終わりに JUnit がルートストアを閉じると [close] でサーバーを止め、
 * result.json を確定する。
 *
 * @property extensionClass このリースの拡張のクラス
 * @property fukurou 所有者
 * @property definition 凍結した設定（宣言したプレイヤーを含む）
 * @property server サーバー（run ディレクトリと result.json）
 */
internal class ServerLease private constructor(
    val extensionClass: Class<out GameServerExtension>,
    val fukurou: Fukurou,
    val definition: ServerDefinition,
    val server: ServerInstance,
) : AutoCloseable {
    /** このサーバーを使っている（beforeAll の後、afterAll の前の）テストクラスの数。 */
    @Volatile
    var refCount: Int = 0
        private set

    /** 最後に使った順番（大きいほど新しい。退避の順序に使う）。 */
    @Volatile
    var lastUsed: Long = 0
        private set

    /** 起動（または参加）の失敗。あれば以後のクラスにもそのまま返す（Python と同じく再試行しない）。 */
    @Volatile
    var startFailure: Throwable? = null
        private set

    /** 一度でも起動したか。 */
    @Volatile
    private var started = false

    /** メモリ予算のために止めたか（次の acquire で fresh-server として起動し直す）。 */
    @Volatile
    private var evicted = false

    /** close 済みか。 */
    @Volatile
    private var closed = false

    /** 実行中のテスト（beforeEach で beginTest した後、afterEach まで）。 */
    @Volatile
    var current: TestRun? = null

    /** beforeEach の中（リセット・setUp）で起きた例外。afterEach でテストの結末に使う。 */
    @Volatile
    var beforeEachError: Throwable? = null

    /** メソッドのキー → 計画した識別（id は重複の解消後）。 */
    private val identities = ConcurrentHashMap<String, TestIdentity>()

    /** 計画に載せた tests[].id。 */
    private val plannedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 次に計画に足すテストの順番。 */
    private var nextOrder = 0

    /** サーバーとクライアントのメモリの見積もり（MB）。 */
    val estimateMb: Long = MemoryBudget.estimateMb(definition.serverHeap, definition.clientHeap, definition.players.size)

    /** 宣言したプレイヤー（宣言順 = 参加順）。 */
    val players: List<PlayerProfile> get() = definition.players

    /** サーバーが起動していて、止められる（メモリを使っている）状態か。 */
    val isRunning: Boolean get() = started && !evicted && !closed && startFailure == null && server.deadReason == null

    /** 起動済みか（server を渡してよいか）。 */
    val isStarted: Boolean get() = started && startFailure == null

    /** 以後のテストを走らせない理由（サーバーの死亡・クライアントの再起動の失敗）。 */
    val abortReason: String? get() = server.deadReason

    // --- 使用の数 ------------------------------------------------------------------

    /** テストクラスが使い始める。 */
    @Synchronized
    fun retain() {
        refCount++
        lastUsed = USE_COUNTER.incrementAndGet()
    }

    /** テストクラスが使い終わる。サーバーは予算が退避させるか、ルートストアが閉じるまで動かしたままにする。 */
    @Synchronized
    fun release() {
        if (refCount > 0) refCount--
        lastUsed = USE_COUNTER.incrementAndGet()
    }

    // --- 計画 ----------------------------------------------------------------------

    /**
     * テストクラスのテストのうち、まだ計画に無いものを足す（計画のリスナーが無い実行や、テンプレートの実行）。
     */
    fun planClass(testClass: Class<*>) {
        FukurouExtensions.testMethods(testClass).forEach { method -> identityOf(testClass, method) }
    }

    /**
     * testClass で実行する method の識別。計画に無ければ足す（id が重なれば "<Class>.<id>" にする）。
     */
    @Synchronized
    fun identityOf(testClass: Class<*>, method: Method): TestIdentity {
        val key = TestIdentity.key(testClass, method)
        identities[key]?.let { return it }
        val base = TestIdentity.of(testClass, method, FukurouExtensions.tags(testClass, method))
        // 同じ result の中で重なった id はクラス名で区別する（計画時の dedupeTestIds と同じ形）
        val candidate = if (base.id in plannedIds) "${base.simpleClassName}.${base.id}" else base.id
        val identity = base.copy(id = ResultIds.uniqueRunId(candidate, plannedIds))
        if (identity.id != base.id) server.harness.warn("test id ${base.id} of ${base.source} is already used in ${server.resultId}; writing ${identity.id}")
        addPlanned(identity, identity.id)
        return identity
    }

    /**
     * 実行中のテストの tests[].id。テンプレートの n 回目なら "-n" を付け、まだ計画に無ければ足す。
     */
    @Synchronized
    fun testId(testClass: Class<*>, method: Method, uniqueId: String): String {
        val identity = identityOf(testClass, method)
        val invocation = TestIdentity.invocation(uniqueId)
        val id = TestIdentity.invocationId(identity.id, invocation)
        if (id !in plannedIds) addPlanned(identity, id)
        // テンプレートは "<id>-<n>" として記録するので、計画に載せたコンテナの stub（not run）を取り消す。
        // id は plannedIds に残し、他のテストが同じ id を使わないようにする
        if (invocation != null) server.recorder.unplan(identity.id)
        return id
    }

    /** テストの期限（@GameTimeout、無ければ ServerSpec.testTimeout）。 */
    fun timeoutOf(identity: TestIdentity): Duration = identity.timeoutSeconds?.seconds ?: definition.testTimeout

    /** このテストの前にサーバーを作り直すか（@FreshServer か Isolation.FreshServer）。 */
    fun wantsFresh(identity: TestIdentity): Boolean = identity.fresh || definition.isolation == Isolation.FreshServer

    /** 計画に 1 件足す（result.json は次の書き出しで反映される）。 */
    private fun addPlanned(identity: TestIdentity, id: String) {
        plannedIds += id
        identities.putIfAbsent(identity.key, identity)
        server.recorder.plan(plannedTest(identity, id, nextOrder++))
    }

    /** result.json の計画の 1 件。 */
    private fun plannedTest(identity: TestIdentity, id: String, order: Int): PlannedTest =
        planned(definition, identity, id, order)

    // --- 起動と退避 ------------------------------------------------------------------

    /**
     * 起動していなければ起動し、宣言したプレイヤーを全員参加させて onStarted を呼ぶ。退避の後なら fresh-server で起動し直す。
     *
     * 失敗は run の失敗として result.json に記録し、以後の acquire にもそのまま返す（再試行しない）。
     */
    suspend fun ensureStarted(extension: GameServerExtension) {
        startFailure?.let { throw FukurouException("${server.resultId} failed to start earlier: ${StatusMapper.messageOf(it)}", it) }
        // 死んだサーバーは起動し直さない。各テストが beforeEach で理由付きの skipped になる
        if (closed || server.deadReason != null || (started && !evicted)) return
        var phase = RunFailurePhase.SETUP
        try {
            if (!started) {
                server.start(SessionKind.INITIAL)
                // 参加より前に全員のクライアントをインストールする（suite_run.py:277）
                phase = RunFailurePhase.CLIENT_JOIN
                server.install(players)
                players.forEach { server.join(it) }
            } else {
                server.harness.info("restarting ${server.resultId} after it was stopped to fit the memory budget")
                server.restartFresh()
            }
            started = true
            evicted = false
            phase = RunFailurePhase.SETUP
            onStarted(extension)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            started = true
            startFailure = error
            val message = StatusMapper.messageOf(error)
            // start / join は自分で記録している。記録の無い失敗（インストール・onStarted）だけをここで記録する
            if (server.recorder.build().failure == null) {
                server.harness.error("${phase.name.lowercase()} failed: $message", error)
                server.recorder.runFailed(phase, message)
            }
            // 起動したプロセス（参加に失敗したときのサーバーなど）を残さない
            withContext(NonCancellable) { runCatching { server.stopSession(message) } }
            throw error
        }
    }

    /** 起動より前の失敗（メモリ予算）を run の失敗として記録し、以後の acquire にも返す。 */
    fun failBeforeStart(error: Throwable) {
        startFailure = error
        val message = StatusMapper.messageOf(error)
        server.harness.error("setup failed: $message")
        server.recorder.runFailed(RunFailurePhase.SETUP, message)
    }

    /** セッションの起動（全員の参加）の直後に onStarted を呼ぶ。ステップは harness.log にだけ残る。 */
    suspend fun onStarted(extension: GameServerExtension) {
        withContext(StepScope(run = null)) { extension.started(server) }
    }

    /** メモリ予算のためにサーバーとクライアントを止める。次の acquire で fresh-server として起動し直す。 */
    fun evict() {
        if (!isRunning) return
        server.harness.info("stopping ${server.resultId} to fit the memory budget; it restarts as a fresh server when used again")
        evicted = true
        runBlocking(Dispatchers.IO) {
            runCatching { server.stopSession(null) }.onFailure { server.harness.error("could not stop ${server.resultId}", it) }
        }
    }

    /** サーバーを止めて result.json を確定する（JUnit がルートストアを閉じるとき）。冪等。 */
    override fun close() {
        if (closed) return
        closed = true
        LeaseRegistry.forget(this)
        runBlocking(Dispatchers.IO) { server.stop() }
    }

    /** 表示用。 */
    override fun toString(): String = "ServerLease(${server.resultId})"

    /** 作成と計画の変換。 */
    companion object {
        /** lastUsed の採番（時刻ではなく順番で比べる）。 */
        private val USE_COUNTER = AtomicLong()

        /**
         * run ディレクトリを用意し、計画したテストを not run の stub として result.json に書く（ダウンロードより前）。
         *
         * @param identities 計画順のテスト（id の重複は解消前）
         */
        fun open(
            extensionClass: Class<out GameServerExtension>,
            fukurou: Fukurou,
            definition: ServerDefinition,
            identities: List<TestIdentity>,
        ): ServerLease {
            // 同じ result に同じ id があれば、計画順に "<Class>.<id>" にする（計画から決まるので再現できる）
            val ids = ResultIds.dedupeTestIds(identities.map { it.simpleClassName to it.id })
            val planned = identities.mapIndexed { order, identity -> planned(definition, identity, ids[order], order) }
            val suite = ServerInstance.suiteInfo(
                definition,
                source = "junit:${extensionClass.name}",
                sha256 = TestIdentity.classSha256(extensionClass),
            )
            val server = ServerInstance.open(fukurou, definition, suite, planned)
            val lease = ServerLease(extensionClass, fukurou, definition, server)
            identities.forEachIndexed { index, identity ->
                if (ids[index] != identity.id) server.harness.warn("test id ${identity.id} of ${identity.source} is used more than once; writing ${ids[index]}")
                lease.plannedIds += ids[index]
                lease.identities.putIfAbsent(identity.key, identity.copy(id = ids[index]))
            }
            lease.nextOrder = identities.size
            return lease
        }

        /** 識別と設定から result.json の計画の 1 件を作る。 */
        private fun planned(definition: ServerDefinition, identity: TestIdentity, id: String, order: Int): PlannedTest =
            PlannedTest(
                className = identity.className,
                method = identity.methodName,
                id = id,
                name = identity.name,
                tags = identity.tags,
                order = order,
                source = identity.source,
                sha256 = identity.sha256,
                isolation = if (identity.fresh || definition.isolation == Isolation.FreshServer) IsolationMode.FRESH_SERVER else IsolationMode.RESET,
                timeoutSeconds = (identity.timeoutSeconds?.seconds ?: definition.testTimeout).inWholeMilliseconds / MILLIS_PER_SECOND,
                versions = identity.versions,
                players = definition.players,
            )

        /** ミリ秒 → 秒。 */
        private const val MILLIS_PER_SECOND = 1000.0
    }
}
