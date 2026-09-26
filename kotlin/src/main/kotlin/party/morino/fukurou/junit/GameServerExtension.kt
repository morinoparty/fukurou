package party.morino.fukurou.junit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolutionException
import org.junit.jupiter.api.extension.ParameterResolver
import org.junit.jupiter.api.extension.TestWatcher
import org.opentest4j.TestAbortedException
import party.morino.fukurou.Fukurou
import party.morino.fukurou.FukurouConfig
import party.morino.fukurou.MissingHostPolicy
import party.morino.fukurou.engine.process.HostCheck
import party.morino.fukurou.engine.step.StepScope
import party.morino.fukurou.junit.platform.FukurouExtensions
import party.morino.fukurou.player.PlayerProfile
import party.morino.fukurou.result.model.step.StepPhase
import party.morino.fukurou.result.output.ResultIds
import party.morino.fukurou.result.output.StatusMapper
import party.morino.fukurou.server.GameServer
import party.morino.fukurou.server.ServerDefinition
import party.morino.fukurou.server.ServerSpec
import party.morino.fukurou.server.ServerType
import party.morino.fukurou.server.paper.Paper
import java.lang.reflect.Constructor
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 1 サブクラス = 1 台の独立したサーバー = 1 つの result.json。`@ExtendWith(MyArena::class)` で登録する。
 *
 * JUnit はテストクラスごとにインスタンスを作りうるため、状態はすべてルートストアの ServerLease に置く。
 * 同じ拡張を使うテストクラスはすべて同じサーバーを共有し、サーバーはエンジンの実行の終わり
 * （またはメモリ予算による退避）まで動かしたままにする。
 *
 * ```kotlin
 * class StampArena : GameServerExtension() {
 *     val alice by player("Alice", op = true)
 *     override fun ServerSpec.configure() {
 *         plugins { underTest(PluginSource.systemProperty("minestamp")) }
 *     }
 * }
 * ```
 */
public abstract class GameServerExtension :
    BeforeAllCallback,
    AfterAllCallback,
    BeforeEachCallback,
    BeforeTestExecutionCallback,
    AfterEachCallback,
    LifecycleMethodExecutionExceptionHandler,
    TestWatcher,
    ParameterResolver {
    /** 宣言したプレイヤー（宣言順 = 参加順）。 */
    private val declared = mutableListOf<PlayerProfile>()

    /** サーバーの種類。既定は Paper.fromProperties(config)（CI の matrix が -Pfukurou.minecraftVersion を渡す）。 */
    protected open fun type(config: FukurouConfig): ServerType = Paper.fromProperties(config)

    /** サーバーの宣言（label, plugins, isolation, timeouts…）。起動前に 1 回。label の既定はクラス名の kebab case。 */
    protected abstract fun ServerSpec.configure()

    /** 起動・全員参加の直後（セッションごと）。ステップはハーネスログにだけ残る。 */
    protected open suspend fun GameServer.onStarted() {}

    /** 各テストのリセットの後。phase=beforeEach で記録。fixture("x") { } の中は phase=fixture。 */
    protected open suspend fun GameServer.setUp() {}

    /** 各テストの後（失敗しても呼ばれる）。ステップはハーネスログにだけ残る。 */
    protected open suspend fun GameServer.tearDown() {}

    /** 起動済みのサーバー。beforeAll の前に触ると「@ExtendWith に登録されているか」を示す IllegalStateException。 */
    public val server: GameServer
        get() {
            val lease = LeaseRegistry.lease(javaClass)
            check(lease != null && lease.isStarted) {
                "${javaClass.simpleName} has not started its server; register it with @ExtendWith(${javaClass.simpleName}::class) " +
                    "or @RegisterExtension and use the server from a test or a @BeforeEach method"
            }
            return lease.server
        }

    /** プレイヤーの宣言。宣言順 = 参加順。参照すると参加済みの Player（現在のセッションのもの）を返す。 */
    protected fun player(name: String, op: Boolean = false): PlayerDelegate = PlayerDelegate(PlayerProfile(name, op))

    // --- モジュール内の橋渡し（protected のメンバーは LeaseRegistry などから呼べないため） ---------------------

    /** 使う Fukurou（単体テストは手元の設定に差し替える）。 */
    internal open fun fukurou(): Fukurou = Fukurou.shared

    /** PlayerDelegate から: プレイヤーを宣言順に登録する。 */
    internal fun declare(profile: PlayerProfile) {
        require(declared.none { it.name == profile.name }) { "${profile.name} is declared twice in ${javaClass.simpleName}" }
        declared += profile
    }

    /** サーバーの種類（@MinecraftVersions の判定用。何も起動しない）。 */
    internal fun serverType(config: FukurouConfig): ServerType = type(config)

    /** 種類・configure・宣言したプレイヤーから、凍結した定義を作る。 */
    internal fun buildDefinition(fukurou: Fukurou): ServerDefinition {
        val spec = ServerSpec(type(fukurou.config))
        // 既定の label はクラス名（StampArena → stamp-arena）。configure で上書きできる
        spec.label = ResultIds.kebab(javaClass.simpleName)
        spec.configure()
        return ServerDefinition(fukurou, spec, declared.toList())
    }

    /** onStarted を呼ぶ。 */
    internal suspend fun started(server: GameServer) {
        server.onStarted()
    }

    // --- JUnit のコールバック ----------------------------------------------------------

    /**
     * リースを取得（無ければ作って stub の result.json を書き）、サーバーを起動して宣言したプレイヤーを参加させる。
     */
    final override fun beforeAll(context: ExtensionContext) {
        // Xvfb などが無いホストで skip を選んだなら、run ディレクトリも作らずにクラスごと飛ばす
        if (fukurou().config.missingHost == MissingHostPolicy.SKIP) {
            HostCheck.problemMessage()?.let { throw TestAbortedException(it) }
        }
        val lease = LeaseRegistry.leaseFor(this, context)
        // 宣言は最初のインスタンスのものに固定する。クラスごとに違うと参加者が決まらない
        check(lease.players == declared) {
            "${javaClass.simpleName} declared players ${declared.map { it.name }} here, but ${lease.players.map { it.name }} when its server was created"
        }
        lease.planClass(context.requiredTestClass)
        // GameServer 引数の規則（拡張が 1 つだけのときに限る）のため、クラスに登録された拡張を残す
        registered(context).addIfAbsent(this)
        LeaseRegistry.acquire(lease, this)
    }

    /** このクラスがサーバーを使い終わる。サーバーは動かしたままにする。 */
    final override fun afterAll(context: ExtensionContext) {
        LeaseRegistry.lease(javaClass)?.release()
    }

    /**
     * テストを始める: 必要なら作り直し・クライアントの再起動をし、ログに印を付け、リセットと setUp を行う（§5.3）。
     *
     * BeforeEachCallback の例外は handleBeforeEachMethodExecutionException に届かないので、ここで記録用に残して投げ直す。
     */
    final override fun beforeEach(context: ExtensionContext) {
        val lease = requireLease()
        val testClass = context.requiredTestClass
        val method = context.requiredTestMethod
        val id = lease.testId(testClass, method, context.uniqueId)
        lease.beforeEachError = null
        // 死んだサーバーや起動し直せなかったクライアントでは走らせない（理由付きの skipped）
        lease.abortReason?.let { reason ->
            lease.server.recorder.testSkipped(id, reason)
            throw TestAbortedException(reason)
        }
        val identity = lease.identityOf(testClass, method)
        runBlocking {
            val server = lease.server
            val session = server.sessionIndex
            try {
                server.prepareTest(lease.wantsFresh(identity))
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                // 作り直しや再起動の失敗は run の失敗として記録済み。このテストと残りのテストを理由付きの skipped にする
                server.recorder.skipPending(server.deadReason ?: StatusMapper.messageOf(error))
                throw error
            }
            // fresh-server で新しいセッションになったなら、起動直後の処理をやり直す
            if (server.sessionIndex != session) lease.onStarted(this@GameServerExtension)
            // 同じ JUnit のテストを 2 台のサーバーで実行できるよう、持ち主は JUnit の unique id にする
            val run = server.beginTest(id, lease.timeoutOf(identity), owner = context.uniqueId)
            lease.current = run
            // 利用者の @BeforeEach もこの層で記録する（テスト本体の直前に TEST へ切り替える）
            run.phase = StepPhase.BEFORE_EACH
            try {
                server.resetBeforeTest(run)
                withContext(StepScope(run, StepPhase.BEFORE_EACH)) { server.setUp() }
            } catch (error: Throwable) {
                lease.beforeEachError = error
                throw error
            }
        }
    }

    /** テスト本体の直前: 以後のステップを phase=test で記録する。 */
    final override fun beforeTestExecution(context: ExtensionContext) {
        LeaseRegistry.lease(javaClass)?.current?.phase = StepPhase.TEST
    }

    /** 利用者の @BeforeEach の失敗。層は beforeEach のままなので、そのまま投げ直す（afterEach が記録する）。 */
    final override fun handleBeforeEachMethodExecutionException(context: ExtensionContext, throwable: Throwable) {
        throw throwable
    }

    /**
     * テストを閉じる: tearDown、結末の対応づけ、失敗時の撮影、ログの範囲、result.json の書き直し（§5.3）。
     */
    final override fun afterEach(context: ExtensionContext) {
        val lease = LeaseRegistry.lease(javaClass) ?: return
        // beforeEach がテストを始める前に止まった（中断・作り直しの失敗）なら記録するものは無い
        val run = lease.current ?: return
        runBlocking {
            try {
                // tearDown のステップと失敗は harness.log にだけ残し、テストの結果を変えない
                withContext(StepScope(run = null)) { lease.server.tearDown() }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lease.server.harness.error("tearDown of ${run.testId} failed: ${StatusMapper.messageOf(error)}", error)
            }
            val error = lease.beforeEachError ?: context.executionException.orElse(null)
            try {
                lease.server.finishTest(run, error)
            } finally {
                lease.current = null
                lease.beforeEachError = null
            }
        }
    }

    /** 無効にされたテスト（@Disabled・@MinecraftVersions）を理由付きの skipped として残す。 */
    final override fun testDisabled(context: ExtensionContext, reason: Optional<String>) {
        val lease = LeaseRegistry.lease(javaClass) ?: return
        val method = context.testMethod.orElse(null) ?: return
        val id = lease.testId(context.requiredTestClass, method, context.uniqueId)
        lease.server.recorder.testSkipped(id, reason.orElse(null)?.ifBlank { null } ?: "disabled")
    }

    /** 自分の型と、拡張が 1 つだけ登録されているときの GameServer を解決する。Player は注入しない。 */
    final override fun supportsParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Boolean {
        val type = parameterContext.parameter.type
        if (type == javaClass) return true
        if (type != GameServer::class.java) return false
        val testClass = extensionContext.requiredTestClass
        // beforeAll の前（PER_CLASS のコンストラクタ）は登録の一覧がまだ無いので、注釈から探す
        val registered = registeredOrNull(extensionContext)?.map { it.javaClass } ?: FukurouExtensions.find(testClass)
        val inConstructor = parameterContext.declaringExecutable is Constructor<*>
        gameServerProblem(registered, testClass.simpleName, inConstructor)?.let { throw ParameterResolutionException(it) }
        return registered.singleOrNull() == javaClass
    }

    /** supportsParameter が true を返した引数の値。 */
    final override fun resolveParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Any =
        if (parameterContext.parameter.type == GameServer::class.java) server else this

    /** このクラスのリース（beforeAll の後に必ずある）。 */
    private fun requireLease(): ServerLease =
        checkNotNull(LeaseRegistry.lease(javaClass)) { "${javaClass.simpleName} has no server; its beforeAll did not run" }

    /** テストクラスに登録された fukurou の拡張の一覧（クラスのストア）。無ければ作る。 */
    private fun registered(context: ExtensionContext): CopyOnWriteArrayList<GameServerExtension> {
        @Suppress("UNCHECKED_CAST")
        return context.getStore(ExtensionContext.Namespace.create(LeaseRegistry.NAMESPACE))
            .computeIfAbsent(serversKey(context.requiredTestClass)) { CopyOnWriteArrayList<GameServerExtension>() } as CopyOnWriteArrayList<GameServerExtension>
    }

    /** テストクラスに登録された fukurou の拡張の一覧。beforeAll の前なら null。 */
    private fun registeredOrNull(context: ExtensionContext): List<GameServerExtension>? {
        @Suppress("UNCHECKED_CAST")
        return context.getStore(ExtensionContext.Namespace.create(LeaseRegistry.NAMESPACE)).get(serversKey(context.requiredTestClass)) as List<GameServerExtension>?
    }

    /** 引数の規則。 */
    internal companion object {
        /** クラスのストアのキー（@Nested の内側と外側で一覧を分けるため、クラスを含める）。 */
        private fun serversKey(testClass: Class<*>): String = "fukurou.servers:${testClass.name}"

        /**
         * GameServer の引数を解決できない理由。解決できる（拡張が 1 つだけで、メソッドの引数）なら null（§5.4）。
         *
         * @param registered テストクラスに登録された fukurou の拡張のクラス
         * @param testClassName テストクラスの単純名
         * @param inConstructor コンストラクタの引数か（サーバーは beforeAll で起動するので渡せない）
         */
        fun gameServerProblem(registered: List<Class<*>>, testClassName: String, inConstructor: Boolean): String? {
            val names = registered.map { it.simpleName }
            val hint = if (inConstructor) "; the server starts in beforeAll, so use a method parameter" else ""
            return when {
                // どちらのサーバーか決まらないので、拡張の型で受け取るよう案内する
                registered.size > 1 -> "${registered.size} fukurou servers are registered on $testClassName (${names.joinToString(", ")}); " +
                    "declare the parameter as ${names.dropLast(1).joinToString(", ")} or ${names.last()}$hint"
                inConstructor -> "GameServer cannot be injected into the constructor of $testClassName$hint"
                else -> null
            }
        }
    }
}
