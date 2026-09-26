package party.morino.fukurou.spi

import kotlin.reflect.KClass

/** 能力の目印。種類が提供する操作（PlayerCommands など）はこれを継承したインターフェースにする。 */
public interface Capability

/**
 * 型で引ける能力の集合。
 *
 * @property entries 能力のインターフェース → 実装
 */
public class Capabilities(private val entries: Map<KClass<out Capability>, Capability>) {
    /** kind の能力。無ければ null。 */
    public fun <C : Capability> get(kind: KClass<C>): C? = entries[kind]?.let { kind.java.cast(it) }

    /** 登録されている能力のインターフェース。 */
    public val kinds: Set<KClass<out Capability>> get() = entries.keys

    public companion object {
        /** 空の集合（能力を持たない種類）。 */
        public val EMPTY: Capabilities = Capabilities(emptyMap())

        /** 各実装が実装している Capability のサブインターフェースをすべて登録する（1 クラスで複数の能力を持てる）。 */
        public fun of(vararg capabilities: Capability): Capabilities {
            val entries = linkedMapOf<KClass<out Capability>, Capability>()
            for (capability in capabilities) {
                for (kind in capabilityInterfaces(capability.javaClass)) {
                    // 同じ能力を 2 つの実装が持つと、どちらが使われるか分からなくなる
                    require(kind !in entries) { "capability ${kind.simpleName} is provided twice" }
                    entries[kind] = capability
                }
            }
            return Capabilities(entries)
        }

        /** type が実装する Capability のサブインターフェース（親クラス・親インターフェースもたどる）。kotlin-reflect を使わない。 */
        private fun capabilityInterfaces(type: Class<*>): Set<KClass<out Capability>> {
            val found = linkedSetOf<KClass<out Capability>>()
            val queue = ArrayDeque<Class<*>>().apply { add(type) }
            val seen = mutableSetOf<Class<*>>()
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!seen.add(current)) continue
                // Capability 自身は目印なので登録しない
                if (current.isInterface && current != Capability::class.java && Capability::class.java.isAssignableFrom(current)) {
                    @Suppress("UNCHECKED_CAST")
                    found += (current as Class<out Capability>).kotlin
                }
                current.superclass?.let(queue::add)
                queue.addAll(current.interfaces)
            }
            return found
        }
    }
}
