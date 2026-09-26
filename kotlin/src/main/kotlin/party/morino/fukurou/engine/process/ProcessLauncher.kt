package party.morino.fukurou.engine.process

import java.nio.file.Path

/** setsid で新しいセッション（プロセスグループ）として起動する（start_new_session 相当）。 */
internal object ProcessLauncher {
    /**
     * argv を setsid 越しに起動する。標準入力は /dev/null、標準出力と標準エラーは logFile（先に空にする）。
     *
     * /proc/<pid>/stat の pgrp が pid と違えば warn に書き、子孫を直接止める方式にする。
     *
     * @param name ハーネスログに出す名前
     * @param argv setsid を除いたコマンド
     * @param cwd 作業ディレクトリ
     * @param logFile 出力の書き出し先
     * @param env 追加の環境変数
     * @param warn 警告の書き出し先（harness.log）
     */
    fun launch(
        name: String,
        argv: List<String>,
        cwd: Path,
        logFile: Path,
        env: Map<String, String> = emptyMap(),
        warn: (String) -> Unit = {},
    ): ManagedProcess = TODO("WP2: ProcessLauncher.launch($name, $argv, $cwd, $logFile, $env, $warn)")
}
