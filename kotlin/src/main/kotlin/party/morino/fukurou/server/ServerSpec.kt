package party.morino.fukurou.server

import party.morino.fukurou.FukurouDsl
import party.morino.fukurou.plugin.PluginSetBuilder
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 起動前にだけ触れる設定。種類に共通の項目だけを持つ（server.properties のような種類固有の設定は ServerType の値に置く）。
 *
 * @property type サーバーの種類
 */
@FukurouDsl
public class ServerSpec internal constructor(public val type: ServerType) {
    /** result id の末尾と出力ディレクトリ名。^[a-z0-9]+(?:-[a-z0-9]+)*$、40 文字まで。 */
    public var label: String = "default"

    /** テスト同士の隔離方法。 */
    public var isolation: Isolation = Isolation.Reset()

    /** サーバーの起動を待つ時間（session.py:28）。 */
    public var startTimeout: Duration = 600.seconds

    /** クライアントのインストール（portablemc --dry）を待つ時間（suite_run.py:50）。 */
    public var installTimeout: Duration = 900.seconds

    /** 参加を待つ時間。最初のウィンドウの待ちにも使う（session.py:29）。 */
    public var joinTimeout: Duration = 900.seconds

    /** テスト 1 件の期限（ソフトデッドライン、§4.11）。 */
    public var testTimeout: Duration = 600.seconds

    /** サーバーの -Xmx（server/process.py:13 HEAP）。 */
    public var serverHeap: String = "2G"

    /** クライアントの -Xmx（client.py:105）。 */
    public var clientHeap: String = "1536M"

    /** 宣言したプラグイン（宣言順）。 */
    internal val pluginSet: PluginSetBuilder = PluginSetBuilder()

    /** --server-files と同じく最初にコピーするディレクトリ（宣言順）。 */
    internal val serverFileDirectories: MutableList<Path> = mutableListOf()

    /** プラグインを宣言する。 */
    public fun plugins(block: PluginSetBuilder.() -> Unit) {
        pluginSet.block()
    }

    /** サーバーのディレクトリへ最初にコピーするファイル（--server-files と同じ）。 */
    public fun serverFiles(directory: Path) {
        serverFileDirectories.add(directory)
    }
}
