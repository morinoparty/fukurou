package party.morino.fukurou.engine.paper.command

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/**
 * RCON（Source RCON Protocol）のパケットの符号化と復号（server/rcon.py:44-66）。純粋。
 *
 * パケット = 本体の長さ（int32 LE）+ 本体。本体 = リクエスト ID（int32 LE）+ 種別（int32 LE）+ ペイロード（UTF-8）+ 終端の 2 バイト。
 */
internal object RconCodec {
    /** ログインの種別。 */
    const val LOGIN_TYPE: Int = 3

    /** コマンドの種別。 */
    const val COMMAND_TYPE: Int = 2

    /** 認証に失敗したときにサーバーが返すリクエスト ID。 */
    const val AUTH_FAILED_ID: Int = -1

    /**
     * 1 リクエストのペイロードの上限（バイト）。
     * バニラの RCON サーバーは 1 パケットを 1460 バイトのバッファに読むため、ID・種別・終端・長さの 14 バイトを引いた値。
     */
    const val MAX_PAYLOAD_BYTES: Int = 1446

    /** 応答の本体の長さの上限。壊れた長さで巨大な配列を確保しないための保険。 */
    private const val MAX_RESPONSE_BODY: Int = 1 shl 20

    /** 本体のうちペイロード以外（ID + 種別 + 終端）のバイト数。 */
    private const val BODY_OVERHEAD: Int = 4 + 4 + 2

    /**
     * 復号したパケット。
     *
     * @property requestId リクエスト ID（認証失敗なら -1）
     * @property type パケットの種別
     * @property payload 本文（終端を除き、UTF-8 の不正な並びは置き換える）
     */
    data class Packet(val requestId: Int, val type: Int, val payload: String)

    /** 1 パケットを符号化する。ペイロードが上限を超えれば、送る前に IllegalArgumentException。 */
    fun encode(requestId: Int, type: Int, payload: String): ByteArray {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        // 大きすぎるとサーバー側で切り詰められて別のコマンドになるため、黙って送らずに止める
        require(bytes.size <= MAX_PAYLOAD_BYTES) {
            "command is ${bytes.size} bytes; RCON accepts at most $MAX_PAYLOAD_BYTES — shorten the component"
        }
        val bodyLength = BODY_OVERHEAD + bytes.size
        // 先頭に本体の長さを付け、本体の末尾には終端の 2 バイト（0, 0）を置く
        return ByteBuffer.allocate(4 + bodyLength).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(bodyLength)
            .putInt(requestId)
            .putInt(type)
            .put(bytes)
            .put(0)
            .put(0)
            .array()
    }

    /** input から 1 パケットを読む。途中で接続が閉じたら EOFException。 */
    fun decode(input: InputStream): Packet {
        val length = ByteBuffer.wrap(readExactly(input, 4)).order(ByteOrder.LITTLE_ENDIAN).int
        // ID と種別と終端が入らない長さは壊れたパケット
        if (length < BODY_OVERHEAD || length > MAX_RESPONSE_BODY) throw EOFException("invalid RCON packet length $length")
        val body = ByteBuffer.wrap(readExactly(input, length)).order(ByteOrder.LITTLE_ENDIAN)
        val requestId = body.int
        val type = body.int
        // 終端の 2 バイトを除いた残りが本文。Python の errors="replace" と同じく不正な並びは置き換える
        val payloadBytes = ByteArray(length - BODY_OVERHEAD).also { body.get(it) }
        return Packet(requestId, type, decodeUtf8(payloadBytes))
    }

    /** 不正な並びを U+FFFD に置き換えて UTF-8 を読む。 */
    private fun decodeUtf8(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    /** ちょうど size バイトを読む。足りないまま閉じたら EOFException（rcon.py _read_exactly）。 */
    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val data = input.readNBytes(size)
        if (data.size < size) throw EOFException("RCON connection closed by the server")
        return data
    }
}
