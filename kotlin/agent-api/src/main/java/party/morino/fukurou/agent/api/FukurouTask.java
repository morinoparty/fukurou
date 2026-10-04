package party.morino.fukurou.agent.api;

/**
 * テストから名前で呼べる、サーバー上で動く処理（v3 設計 §1.5）。
 *
 * <p>コンパニオンプラグインが {@link FukurouTasks#register(String, FukurouTask)} で登録し、テストは
 * {@code server.execute(name, args)} で呼ぶ。{@link #run(TaskContext)} は必ずサーバーのメインスレッドで呼ばれる。
 */
@FunctionalInterface
public interface FukurouTask {
    /**
     * 処理を実行する。
     *
     * <p>戻り値はエージェントが JSON にしてテストへ返す（Gson の {@code JsonElement} はそのまま、
     * それ以外は v3 設計 §1.4 の規則で変換する）。{@code java.util.concurrent.CompletionStage} を返すと、
     * 完了するまで（リクエストの timeoutMs まで）待ってからその値を返す。
     *
     * @param context 呼び出しの情報（タスク名と引数）
     * @return テストに返す値（null でもよい）
     * @throws Exception 投げた例外はテスト側で ServerTaskException になる
     */
    Object run(TaskContext context) throws Exception;
}
