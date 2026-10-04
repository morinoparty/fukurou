package party.morino.fukurou.agent;

/** 応答の {@code error.type}（v3 設計 §1.2）。 */
enum ErrorType {
    /** hello のトークンが違う、または hello より前に別の op が来た。 */
    AUTH("auth"),
    /** 引数の誤り・未知の op・壊れた JSON。 */
    BAD_REQUEST("bad_request"),
    /** プレイヤー・ワールド・タスク・イベントのクラスが無い。 */
    NOT_FOUND("not_found"),
    /** タスクが投げた。 */
    TASK_FAILED("task_failed"),
    /** timeoutMs 以内にメインスレッドで実行できなかった。 */
    TIMEOUT("timeout"),
    /** エージェント自身の誤り。 */
    INTERNAL("internal");

    /** プロトコルに出す名前。 */
    final String wireName;

    ErrorType(String wireName) {
        this.wireName = wireName;
    }
}
