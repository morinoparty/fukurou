package party.morino.fukurou.junit.annotation

import org.junit.jupiter.api.extension.ExtendWith

/**
 * このテストを走らせる Minecraft のバージョンの範囲（"1.21.9"、"1.21.6-"、"1.21.6-1.21.11"）。
 *
 * 範囲の外のバージョンのサーバーでは、テストを "versions: <spec> does not include <version>" の理由で skipped にする
 * （scenario の versions: と同じ）。メソッドの指定がクラスの指定より優先する。
 *
 * @property spec バージョンの範囲
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@ExtendWith(MinecraftVersionCondition::class)
public annotation class MinecraftVersions(val spec: String)
