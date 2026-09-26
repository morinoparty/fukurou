package party.morino.fukurou.engine.paper.command

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import party.morino.fukurou.error.ServerUnavailableException
import party.morino.fukurou.server.CommandResponse
import party.morino.fukurou.spi.CommandChannel
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * RCON のコマンド経路（server/rcon.py、server/process.py:86-94）。
 *
 * コマンドごとに TCP 接続を開いて認証し、1 つのコマンドを送って 1 つの応答を読む。
 * 接続を共有しないので、複数のレーンから同時に呼んでも安全。
 *
 * @property host 接続先（127.0.0.1）
 * @property port rcon.port
 * @property password rcon.password
 * @property timeout 接続と読み取りのそれぞれの待ち時間（rcon.py:21 と同じ 10 秒）
 */
internal class RconChannel(
    private val host: String,
    private val port: Int,
    private val password: String,
    private val timeout: Duration = 10.seconds,
) : CommandChannel {
    override val repliesToCommands: Boolean get() = true

    override suspend fun send(command: String): CommandResponse {
        // 大きすぎるコマンドは接続する前に弾く（IllegalArgumentException はテストの誤りとしてそのまま投げる）
        val request = RconCodec.encode(COMMAND_ID, RconCodec.COMMAND_TYPE, command)
        val login = RconCodec.encode(LOGIN_ID, RconCodec.LOGIN_TYPE, password)
        // ソケットの I/O は呼び出し元のスレッドを塞がないよう IO で行い、割り込み（キャンセル）で止められるようにする
        val text = runInterruptible(Dispatchers.IO) { exchange(login, request) }
        return CommandResponse(command, text)
    }

    /** 接続・認証・コマンドの送信・応答の受信を 1 本の接続で行う。 */
    private fun exchange(login: ByteArray, request: ByteArray): String {
        try {
            Socket().use { socket ->
                val millis = timeout.inWholeMilliseconds.toInt()
                socket.connect(InetSocketAddress(host, port), millis)
                // 応答が来ないまま固まらないよう、読み取りにも期限を付ける
                socket.soTimeout = millis
                val output = socket.getOutputStream()
                val input = socket.getInputStream()
                output.write(login)
                output.flush()
                // 認証に失敗するとサーバーはリクエスト ID を -1 にして返す
                if (RconCodec.decode(input).requestId == RconCodec.AUTH_FAILED_ID) {
                    throw ServerUnavailableException("RCON authentication failed")
                }
                output.write(request)
                output.flush()
                // Python 版と同じく応答は 1 パケットだけ読む
                return RconCodec.decode(input).payload
            }
        } catch (error: IOException) {
            // プロセスの生死はここでは分からないので、エンジン側で "is not running" を補う
            throw ServerUnavailableException("RCON at $host:$port did not answer (${error.javaClass.simpleName}: ${error.message})")
        }
    }

    /** 接続はコマンドごとに閉じているので、閉じるものは無い。 */
    override fun close() {}

    override fun toString(): String = "RconChannel($host:$port)"

    private companion object {
        /** ログインのリクエスト ID（rcon.py と同じく 1 から数える）。 */
        private const val LOGIN_ID = 1

        /** コマンドのリクエスト ID。 */
        private const val COMMAND_ID = 2
    }
}
