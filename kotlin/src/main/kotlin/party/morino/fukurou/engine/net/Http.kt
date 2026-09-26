package party.morino.fukurou.engine.net

import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import party.morino.fukurou.error.FukurouException
import party.morino.fukurou.error.SetupException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Properties
import java.time.Duration as JavaDuration

/**
 * HTTP での取得（net.py:30-57）。java.net.http の HttpClient を 1 つ使い、リダイレクトは通常どおり追う。
 *
 * @property userAgent 送る User-Agent。Paper の API は連絡先の分かる User-Agent を求めている
 * @property redirect リダイレクトの扱い（GitHub のアセットだけ NEVER を使う）
 */
internal class Http(
    val userAgent: String = DEFAULT_USER_AGENT,
    redirect: HttpClient.Redirect = HttpClient.Redirect.NORMAL,
) {
    /** 共有のクライアント。接続は 60 秒で打ち切る。 */
    val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(redirect)
        .connectTimeout(TIMEOUT)
        .build()

    /** User-Agent と追加のヘッダーを付けた GET リクエストを作る。 */
    fun request(url: String, headers: Map<String, String> = emptyMap()): HttpRequest {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(TIMEOUT)
            .header("User-Agent", userAgent)
            .GET()
        // 呼び出し側のヘッダー（Accept、Authorization など）を足す
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    /** request を送る。キャンセルされると送信中の要求も取り消される（sendAsync + await）。 */
    suspend fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> =
        try {
            client.sendAsync(request, handler).await()
        } catch (error: java.io.IOException) {
            throw SetupException("GET ${request.uri()} failed: $error", error)
        } catch (error: java.util.concurrent.CompletionException) {
            // sendAsync の失敗は CompletionException に包まれて届くことがある
            throw SetupException("GET ${request.uri()} failed: ${error.cause ?: error}", error.cause ?: error)
        }

    /** URL の本文を文字列で取得する。404 は [NotFound]、それ以外の失敗は SetupException。 */
    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val response = send(request(url, headers), HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        if (status >= 400) {
            // API のエラーメッセージを短く取り出してメッセージに含める（net.py:115 _error_detail）
            val detail = response.body().trim().take(ERROR_DETAIL_LIMIT).ifEmpty { "HTTP $status" }
            val message = "GET $url failed with HTTP $status: $detail"
            // 存在しないバージョンやビルドを呼び出し側で入力の誤りとして扱えるよう、404 だけ別の型にする
            throw if (status == 404) NotFound(message) else SetupException(message)
        }
        return response.body()
    }

    /** URL から JSON を取得する。JSON でなければ SetupException。 */
    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement {
        val body = getText(url, headers)
        return try {
            JSON.parseToJsonElement(body)
        } catch (error: IllegalArgumentException) {
            throw SetupException("GET $url did not return JSON: ${error.message}", error)
        }
    }

    /**
     * HTTP 404（net.py NotFoundError）。存在しないバージョンやビルドの指定を入力の誤りとして扱えるようにする。
     *
     * @param message 失敗した URL と応答の本文を含むメッセージ
     */
    class NotFound(message: String) : FukurouException(message)

    companion object {
        /** 接続と応答ヘッダーまでの待ち時間（net.py TIMEOUT_SECONDS）。 */
        val TIMEOUT: JavaDuration = JavaDuration.ofSeconds(60)

        /** エラー応答の本文から取り出す最大の文字数。 */
        private const val ERROR_DETAIL_LIMIT = 500

        /** 未知のキーを無視する JSON の読み手（API の応答には使わないキーが多い）。 */
        val JSON: Json = Json { ignoreUnknownKeys = true }

        /** ビルド時に生成した version.properties の fukurou のバージョン。無ければ dev。 */
        val VERSION: String by lazy {
            val properties = Properties()
            // 生成したリソースが無い環境（IDE の実行など）でも動くようにする
            Http::class.java.getResourceAsStream("/META-INF/fukurou/version.properties")?.use { properties.load(it) }
            properties.getProperty("version") ?: "dev"
        }

        /** Paper の API が求める、連絡先の分かる User-Agent。 */
        val DEFAULT_USER_AGENT: String get() = "fukurou/$VERSION (+https://github.com/morinoparty/fukurou)"
    }
}
