package party.morino.fukurou.agent.api;

/** {@link FukurouTask} の 1 回の呼び出しの情報。 */
public interface TaskContext {
    /**
     * 呼ばれたタスクの名前を返す。
     *
     * @return 登録したときの名前
     */
    String task();

    /**
     * テストが渡した引数を JSON の文字列のまま返す。
     *
     * @return 引数の JSON。引数が無ければ {@code "null"}
     */
    String argsJson();

    /**
     * 引数を type の値として読む（エージェントが持つ Gson で変換する）。
     *
     * @param type 読みたい型
     * @param <T> 読みたい型
     * @return 変換した値。引数が無ければ null
     * @throws IllegalArgumentException 引数を type として読めないとき
     */
    <T> T args(Class<T> type);

    /**
     * サーバーのログに 1 行書く（エージェントのロガーにタスク名を付けて出す）。
     *
     * @param message 書く内容
     */
    void log(String message);
}
