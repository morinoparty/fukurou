package party.morino.fukurou.plugin

import java.nio.file.Path

/**
 * ローカルの jar。
 *
 * @property path jar のパス（起動時に存在しなければ SetupException）
 */
public data class LocalJarSource(val path: Path) : PluginSource
