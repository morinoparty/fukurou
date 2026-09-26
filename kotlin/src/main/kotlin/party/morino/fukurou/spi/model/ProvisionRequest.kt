package party.morino.fukurou.spi.model

import party.morino.fukurou.spi.plugin.ResolvedPlugin
import java.nio.file.Path

/**
 * セッションの準備の依頼。
 *
 * @property serverDir <workDir>/servers/<runId>/server（provision の前にエンジンが空にする）
 * @property sessionIndex セッションの番号（0 始まり）
 * @property plugins ダウンロードと検査を済ませたプラグイン
 * @property serverFiles 最初にコピーするディレクトリ
 * @property maxPlayers 参加させるプレイヤーの数
 * @property serverHeap サーバーの -Xmx
 * @property serverJava サーバーを起動する java
 */
public data class ProvisionRequest(
    val serverDir: Path,
    val sessionIndex: Int,
    val plugins: List<ResolvedPlugin>,
    val serverFiles: Path?,
    val maxPlayers: Int,
    val serverHeap: String,
    val serverJava: Path,
)
