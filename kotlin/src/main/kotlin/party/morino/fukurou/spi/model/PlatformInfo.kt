package party.morino.fukurou.spi.model

/**
 * result.json の minecraft / java に書く情報。
 *
 * @property serverKind minecraft.server（"paper"）
 * @property version minecraft.version
 * @property build minecraft.build
 * @property channel minecraft.channel（小文字）
 * @property javaMajor java.server
 */
public data class PlatformInfo(
    val serverKind: String,
    val version: String,
    val build: Int?,
    val channel: String?,
    val javaMajor: Int?,
)
