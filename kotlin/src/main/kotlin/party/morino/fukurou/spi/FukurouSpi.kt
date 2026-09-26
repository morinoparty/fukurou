package party.morino.fukurou.spi

/** サーバーの種類を実装するための SPI の目印。マイナーリリースで変わりうるので、使う側に明示的なオプトインを求める。 */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "Server type SPI; may change in minor releases")
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class FukurouSpi
