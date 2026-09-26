package party.morino.fukurou.junit

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtensionContext
import party.morino.fukurou.Fukurou
import party.morino.fukurou.junit.platform.FukurouExtensions
import party.morino.fukurou.junit.platform.TestIdentity
import party.morino.fukurou.result.output.ArtifactLayout
import party.morino.fukurou.result.output.ResultIds
import party.morino.fukurou.server.ServerDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * JVM 内のリース（拡張のクラスごとのサーバー）と、計画・メモリ予算・後片付けをまとめる（§5.2）。
 *
 * リース自体は JUnit のルートストアに置く（エンジンの実行の終わりに JUnit が閉じる）。ここはそれを拡張のクラスから
 * 引けるようにし、予算に収まるよう使われていないリースを止める。
 */
internal object LeaseRegistry {
    /** JUnit のストアの名前空間。 */
    const val NAMESPACE: String = "party.morino.fukurou"

    /** ルートストアで後片付けを受け持つ値のキー。 */
    private const val CLOSER_KEY = "fukurou.registry"

    /** 計画のリスナーが集めた、拡張のクラス → テスト（計画順）。 */
    @Volatile
    private var plan: Map<Class<out GameServerExtension>, List<TestIdentity>> = emptyMap()

    /** 生きているリース（拡張のクラス → リース）。 */
    private val leases = ConcurrentHashMap<Class<out GameServerExtension>, ServerLease>()

    /** リースが使った Fukurou（実行の終わりに閉じる）。リースは close で leases から外れるので別に持つ。 */
    private val owners: MutableSet<Fukurou> = ConcurrentHashMap.newKeySet()

    /** この実行で古い run ディレクトリを片付けたか。 */
    @Volatile
    private var pruned = false

    /** この実行のメモリ予算（最初の acquire で決める）。 */
    @Volatile
    private var budget: MemoryBudget? = null

    /** 計画のリスナーから: この実行で走るテストを拡張のクラスごとに受け取る。 */
    fun plan(planned: Map<Class<out GameServerExtension>, List<TestIdentity>>) {
        plan = planned.mapValues { it.value.toList() }
    }

    /** 拡張のクラスのリース（まだ作っていなければ null）。 */
    fun lease(extensionClass: Class<*>): ServerLease? = leases[extensionClass]

    /**
     * 拡張のリースを取得する。無ければ作り、計画したテストの stub を result.json に書く（ダウンロードより前）。
     */
    fun leaseFor(extension: GameServerExtension, context: ExtensionContext): ServerLease {
        val store = context.root.getStore(ExtensionContext.Namespace.create(NAMESPACE))
        // 後片付けの値を先に置く。ルートストアは後に置いた値から閉じるので、全リースの停止の後に Fukurou を閉じられる
        store.computeIfAbsent(CLOSER_KEY) { AutoCloseable(::closeAll) }
        return store.computeIfAbsent(extension.javaClass, { create(extension, context.requiredTestClass) }, ServerLease::class.java)
    }

    /** リースを作る。この実行で最初のリースなら、先に古い run ディレクトリを片付ける。 */
    private fun create(extension: GameServerExtension, testClass: Class<*>): ServerLease {
        val fukurou = extension.fukurou()
        val definition = extension.buildDefinition(fukurou)
        pruneOnce(fukurou, extension.javaClass, definition)
        // 計画のリスナーが無い（IDE の一部など）ときは、今のクラスのテストだけを計画にする（他のクラスは beforeAll で足す）
        val identities = plan[extension.javaClass] ?: FukurouExtensions.testMethods(testClass)
            .map { TestIdentity.of(testClass, it, FukurouExtensions.tags(testClass, it)) }
        val lease = ServerLease.open(extension.javaClass, fukurou, definition, identities)
        leases[extension.javaClass] = lease
        owners += fukurou
        return lease
    }

    /** 計画のリスナーがこの拡張のテストを計画したか（したなら、フィルタで外れたテストを足してはいけない）。 */
    fun hasPlan(extensionClass: Class<*>): Boolean = extensionClass in plan

    /**
     * 使い始める: 予算を確かめ、足りなければ使われていないリースを古い順に止めてから起動する（§5.2）。
     *
     * @throws ServerBudgetException 使用中のサーバーだけで予算を超える（何も起動しない）
     */
    @Synchronized
    fun acquire(lease: ServerLease, extension: GameServerExtension) {
        lease.retain()
        if (!lease.isRunning && lease.startFailure == null && lease.abortReason == null) {
            val others = leases.values.filter { it !== lease && it.isRunning }
            val budget = budgetOf(lease.fukurou)
            // 同じクラス（や外側のクラス）が使っているサーバーは止められない
            val inUse = others.filter { it.refCount > 0 }.associate { it.server.resultId to it.estimateMb }
            val idle = others.filter { it.refCount == 0 }.sortedBy { it.lastUsed }
            val evictions = try {
                budget.evictions(lease.server.resultId, lease.estimateMb, inUse, idle, { it.estimateMb }, { it.server.resultId })
            } catch (error: ServerBudgetException) {
                // 何も起動していないが、なぜ走らなかったかを result.json に残す（再試行はしない）
                lease.failBeforeStart(error)
                throw error
            }
            evictions.forEach(ServerLease::evict)
        }
        runBlocking { lease.ensureStarted(extension) }
    }

    /** close したリースを外す。 */
    fun forget(lease: ServerLease) {
        leases.remove(lease.extensionClass, lease)
    }

    /**
     * エンジンの実行の終わり: すべてのリースを閉じ（サーバーを止めて result.json を確定し）、使った Fukurou を閉じる。
     */
    fun closeAll() {
        // JUnit は後に置いた値から閉じるので、通常はリースが先に閉じている。残っていれば（例外などで）ここで閉じる
        leases.values.toList().forEach { lease -> runCatching { lease.close() } }
        val closing = owners.toList()
        closing.forEach { runCatching { it.close() } }
        // 同じ JVM で次の実行（入れ子のランチャーなど）があっても、前の実行の状態を持ち越さない
        owners.removeAll(closing.toSet())
        leases.clear()
        pruned = false
        budget = null
    }

    /** メモリ予算。fukurou.memoryBudgetMb、無ければ最初の acquire の時点の MemAvailable の 9 割。 */
    private fun budgetOf(fukurou: Fukurou): MemoryBudget =
        budget ?: MemoryBudget(
            fukurou.config.memoryBudgetMb
                ?: runCatching { MemoryBudget.fromMemInfo(Files.readString(Path.of("/proc/meminfo"))) }.getOrNull()
                // 空きメモリが分からない（Linux 以外）なら制限しない。起動は HostCheck が止める
                ?: Long.MAX_VALUE,
        ).also { budget = it }

    /**
     * この実行の最初のリースを作る前に、この JVM で計画していない古い run ディレクトリ（印のあるもの）を消す。
     * 前の実行の結果が今回の artifact に混ざらないようにする。
     */
    private fun pruneOnce(fukurou: Fukurou, extensionClass: Class<out GameServerExtension>, definition: ServerDefinition) {
        if (pruned) return
        pruned = true
        val others = plan.keys - extensionClass
        val keep = others.mapNotNull { kind ->
            // run id は種類・版・label から決まる。作れない拡張の分は（どうせ作り直すので）残さなくてよい
            runCatching { FukurouExtensions.instantiate(kind)?.buildDefinition(fukurou)?.let(::runIdOf) }.getOrNull()
        }.toSet() + runIdOf(definition)
        ArtifactLayout.pruneStale(fukurou.config.outDir, keep)
    }

    /** 定義の run id（JVM 内の重複で付く -2 などは含まない）。 */
    private fun runIdOf(definition: ServerDefinition): String =
        ResultIds.runId(definition.type.id, definition.type.minecraftVersion.id, definition.label)
}
