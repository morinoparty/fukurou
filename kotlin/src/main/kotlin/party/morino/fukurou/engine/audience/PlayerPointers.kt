package party.morino.fukurou.engine.audience

import net.kyori.adventure.identity.Identity
import net.kyori.adventure.pointer.Pointers
import net.kyori.adventure.text.Component
import java.util.Locale
import java.util.UUID

/** Player の Adventure の pointers（§1.9）。 */
internal object PlayerPointers {
    /**
     * NAME / UUID / DISPLAY_NAME / LOCALE を持つ Pointers。
     *
     * LOCALE は options.txt の lang:en_us（ClientOptions）に合わせて Locale.US にする。
     */
    fun of(name: String, uuid: UUID): Pointers =
        Pointers.builder()
            .withStatic(Identity.NAME, name)
            .withStatic(Identity.UUID, uuid)
            // 表示名はサーバーから取れないので、名前をそのまま使う
            .withStatic(Identity.DISPLAY_NAME, Component.text(name))
            .withStatic(Identity.LOCALE, Locale.US)
            .build()
}
