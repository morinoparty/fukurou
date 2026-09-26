package party.morino.fukurou.engine.log

/** ANSI のエスケープシーケンスの除去（runner/process.py:13 ANSI_ESCAPE）。 */
internal object Ansi {
    /** ANSI のカラーコード。改行を含まないので、行ごとに除いてもファイル全体で除いても結果は同じ。 */
    val ESCAPE: Regex = Regex("\u001B\\[[0-9;]*[A-Za-z]")

    /**
     * text から ANSI のカラーコードを取り除く。ログ照合の邪魔になるため。
     *
     * @param text 元のテキスト
     * @return カラーコードを除いたテキスト
     */
    fun strip(text: String): String = ESCAPE.replace(text, "")
}
