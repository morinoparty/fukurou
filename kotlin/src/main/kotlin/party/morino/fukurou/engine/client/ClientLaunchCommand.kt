package party.morino.fukurou.engine.client

import java.net.InetSocketAddress
import java.nio.file.Path

/**
 * PortableMC の引数（runner/client.py:105 の _command）。純粋。
 *
 * @property executable PortableMC の実行ファイル
 * @property mainDir 共有の Minecraft キャッシュ（--main-dir）
 * @property version 起動する Minecraft のバージョン
 * @property clientDir このプレイヤーの .minecraft（--mc-dir）
 * @property heap クライアントの -Xmx（"1536M" など）
 * @property username プレイヤー名
 * @property java クライアントを動かす java（null なら PortableMC に任せる）
 */
internal data class ClientLaunchCommand(
    val executable: Path,
    val mainDir: Path,
    val version: String,
    val clientDir: Path,
    val heap: String,
    val username: String,
    val java: Path? = null,
) {
    /** 共通の引数。 */
    fun base(): List<String> = buildList {
        add(executable.toString())
        // 機械向けの出力にして、ログに進捗バーの制御文字が混ざらないようにする
        addAll(listOf("--output", "machine", "--main-dir", mainDir.toString()))
        addAll(listOf("start", version, "--mc-dir", clientDir.toString()))
        // 複数クライアントを同じランナーで動かすため、ヒープは控えめにする
        add("--jvm-arg=-Xms512M,-Xmx$heap")
        addAll(listOf("--resolution", ClientOptions.RESOLUTION, "--username", username))
        // java を指定したときだけ PortableMC の選ぶ JVM を上書きする
        java?.let { addAll(listOf("--jvm", it.toString())) }
    }

    /** クライアント本体とアセットをダウンロードだけする（--dry）。 */
    fun install(): List<String> = base() + "--dry"

    /** Quick Play で address のサーバーへ直接参加させる。 */
    fun launch(address: InetSocketAddress): List<String> =
        base() + listOf("--join-server", address.hostString, "--join-server-port", address.port.toString())
}
