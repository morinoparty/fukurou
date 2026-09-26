package party.morino.fukurou.engine.audience

import java.util.UUID

/** オフラインモードのサーバーがプレイヤーに付ける UUID。 */
internal object OfflineUuid {
    /** UUID.nameUUIDFromBytes("OfflinePlayer:<name>")（サーバーの UUIDUtil.createOfflinePlayerUUID と同じ）。 */
    fun of(name: String): UUID = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
}
