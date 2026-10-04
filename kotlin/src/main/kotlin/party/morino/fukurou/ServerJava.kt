package party.morino.fukurou

import java.nio.file.Path as FilePath

/** サーバーを起動する java の選び方（fukurou.serverJava、v3 設計 §3）。 */
public sealed interface ServerJava {
    /** Mojang の要求とプラグインの class major から必要な major を求め、合う JDK を使う（無ければダウンロード）。 */
    public data object Auto : ServerJava

    /** テストを動かしている JVM の java。 */
    public data object Current : ServerJava

    /** 指定の java の実行ファイル。 */
    public data class Path(val executable: FilePath) : ServerJava
}
