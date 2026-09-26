package party.morino.fukurou.plugin

import party.morino.fukurou.FukurouDsl

/** プラグインの宣言（dependencies.py のモデル）。宣言順にサーバーへ入れる。 */
@FukurouDsl
public class PluginSetBuilder internal constructor() {
    /** 宣言したプラグイン（role to source）。role は result.json の plugins[].role の値。 */
    internal val declared: MutableList<Pair<String, PluginSource>> = mutableListOf()

    /** テスト対象のプラグイン（role "under-test"）。 */
    public fun underTest(source: PluginSource) {
        declared.add(UNDER_TEST to source)
    }

    /** 依存するプラグイン（role "dependency"）。 */
    public fun dependency(source: PluginSource) {
        declared.add(DEPENDENCY to source)
    }

    internal companion object {
        /** テスト対象の role。 */
        internal const val UNDER_TEST = "under-test"

        /** 依存の role。 */
        internal const val DEPENDENCY = "dependency"
    }
}
