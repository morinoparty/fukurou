package party.morino.fukurou.server

import party.morino.fukurou.spi.FukurouSpi
import party.morino.fukurou.spi.PlatformServices
import party.morino.fukurou.spi.ServerPlatform
import party.morino.fukurou.version.MinecraftVersion

/**
 * テストが選ぶサーバーの種類。値として比較・表示でき、エンジン側の戦略（ServerPlatform）を作る。
 *
 * 別モジュールで種類（Velocity、Minestom など）を追加できるよう sealed にはしない。
 */
public interface ServerType {
    /** result id の接頭辞と result.minecraft.server に書く名前。^[a-z][a-z0-9]*$ */
    public val id: String

    /** プレイヤーのクライアントを起動する Minecraft のバージョン。 */
    public val minecraftVersion: MinecraftVersion

    /** 1 サーバー（1 リース）につき 1 回呼ばれる。 */
    @FukurouSpi
    public fun createPlatform(services: PlatformServices): ServerPlatform
}
