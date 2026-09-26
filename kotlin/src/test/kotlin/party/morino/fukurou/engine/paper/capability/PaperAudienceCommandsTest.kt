package party.morino.fukurou.engine.paper.capability

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.sound.SoundStop
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import net.kyori.adventure.title.TitlePart
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.fukurou.engine.paper.command.ComponentCodec
import party.morino.fukurou.version.MinecraftVersion
import java.time.Duration

class PaperAudienceCommandsTest {
    private val commands = PaperAudienceCommands(ComponentCodec(MinecraftVersion("26.3")))
    private val hello = Component.text("hello")

    private fun List<party.morino.fukurou.spi.model.CommandCall>.texts() = map { it.command }

    @Test
    @DisplayName("tellraw, actionbar and title parts")
    fun messagesAndTitles() {
        assertEquals(listOf("tellraw Alice \"hello\""), commands.message("Alice", hello).texts())
        assertEquals(listOf("title Alice actionbar \"hello\""), commands.actionBar("Alice", hello).texts())
        val times = Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(1000))
        assertEquals(listOf("title Alice times 10 60 20"), commands.titlePart("Alice", TitlePart.TIMES, times).texts())
        assertEquals(listOf("title Alice subtitle \"hello\""), commands.titlePart("Alice", TitlePart.SUBTITLE, hello).texts())
        assertEquals(listOf("title Alice title \"hello\""), commands.titlePart("Alice", TitlePart.TITLE, hello).texts())
        assertEquals(listOf("title Alice clear"), commands.clearTitle("Alice").texts())
        assertEquals(listOf("title Alice reset"), commands.resetTitle("Alice").texts())
    }

    @Test
    @DisplayName("playsound at the player and at coordinates; stopsound forms")
    fun sounds() {
        val sound = Sound.sound(Key.key("entity.experience_orb.pickup"), Sound.Source.MASTER, 1f, 0.5f)
        assertEquals(
            listOf("execute as Alice at @s run playsound minecraft:entity.experience_orb.pickup master @s ~ ~ ~ 1 0.5"),
            commands.playSound("Alice", sound, null).texts(),
        )
        assertEquals(
            listOf("playsound minecraft:entity.experience_orb.pickup master Alice 1.5 -60 2 1 0.5"),
            commands.playSound("Alice", sound, Triple(1.5, -60.0, 2.0)).texts(),
        )
        assertEquals(listOf("stopsound Alice"), commands.stopSound("Alice", SoundStop.all()).texts())
        assertEquals(listOf("stopsound Alice music"), commands.stopSound("Alice", SoundStop.source(Sound.Source.MUSIC)).texts())
        assertEquals(
            listOf("stopsound Alice * minecraft:block.note_block.harp"),
            commands.stopSound("Alice", SoundStop.named(Key.key("block.note_block.harp"))).texts(),
        )
    }

    @Test
    @DisplayName("Boss bar create, update, viewers and remove")
    fun bossBar() {
        val id = Key.key("fukurou", "bar-1")
        val bar = BossBar.bossBar(hello, 0.25f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10)
        assertEquals(
            listOf(
                "bossbar add fukurou:bar-1 \"hello\"",
                "bossbar set fukurou:bar-1 max 1000",
                "bossbar set fukurou:bar-1 color red",
                "bossbar set fukurou:bar-1 style notched_10",
                "bossbar set fukurou:bar-1 value 250",
            ),
            commands.bossBarCreate(id, bar).texts(),
        )
        assertEquals("bossbar set fukurou:bar-1 name \"hello\"", commands.bossBarUpdate(id, bar).texts().first())
        assertEquals(listOf("bossbar set fukurou:bar-1 players"), commands.bossBarViewers(id, emptyList()).texts())
        assertEquals(listOf("bossbar set fukurou:bar-1 players Alice"), commands.bossBarViewers(id, listOf("Alice")).texts())
        assertEquals(
            listOf(
                "tag @a remove fukurou.bar-1",
                "tag Alice add fukurou.bar-1",
                "tag Bob add fukurou.bar-1",
                "bossbar set fukurou:bar-1 players @a[tag=fukurou.bar-1]",
            ),
            commands.bossBarViewers(id, listOf("Alice", "Bob")).texts(),
        )
        assertEquals(
            listOf("bossbar remove fukurou:bar-1", "tag @a remove fukurou.bar-1"),
            commands.bossBarRemove(id).texts(),
        )
    }
}
