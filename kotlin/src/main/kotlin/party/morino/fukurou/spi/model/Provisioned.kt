package party.morino.fukurou.spi.model

import java.net.InetSocketAddress
import java.nio.file.Path

/**
 * 準備の済んだセッションの起動方法。
 *
 * @property command 起動コマンド（setsid は付けない。エンジンが付ける）
 * @property workingDir 作業ディレクトリ
 * @property environment 追加の環境変数
 * @property joinAddress クライアントの接続先
 * @property channelEndpoint コマンド経路の接続先（Rcon(port, password) など）。エンジンは中身を見ない
 * @property bundlerLock 起動から準備完了まで持つロック（Paper: cache/paper-bundler/<ver>）
 */
public data class Provisioned(
    val command: List<String>,
    val workingDir: Path,
    val environment: Map<String, String>,
    val joinAddress: InetSocketAddress,
    val channelEndpoint: ChannelEndpoint?,
    val bundlerLock: Path?,
)
