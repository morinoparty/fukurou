package party.morino.fukurou.agent;

/**
 * 1 つのリクエストの失敗。{@code {"ok":false,"error":…}} の応答になる。
 *
 * <p>{@link #remote} があれば、その例外のクラス名とスタックトレースを {@code exception} / {@code stackTrace} に出す。
 */
final class AgentException extends Exception {
    private static final long serialVersionUID = 1L;

    /** エラーの種類。 */
    final ErrorType type;

    /** 応答の exception / stackTrace に出す例外（無ければ null）。 */
    final transient Throwable remote;

    /** 失敗したリクエストの id（分からなければ null）。 */
    final Long requestId;

    AgentException(ErrorType type, String message) {
        this(type, message, null, null);
    }

    AgentException(ErrorType type, String message, Throwable remote) {
        this(type, message, remote, null);
    }

    AgentException(ErrorType type, String message, Throwable remote, Long requestId) {
        super(message, null, false, false);
        this.type = type;
        this.remote = remote;
        this.requestId = requestId;
    }

    static AgentException badRequest(String message) {
        return new AgentException(ErrorType.BAD_REQUEST, message);
    }

    static AgentException notFound(String message) {
        return new AgentException(ErrorType.NOT_FOUND, message);
    }

    /** 予期しない例外を internal にする。 */
    static AgentException internal(Throwable cause) {
        return new AgentException(ErrorType.INTERNAL, String.valueOf(cause), cause);
    }

    /** タスクが投げた例外を task_failed にする（ExecutionException などの包みは外す）。 */
    static AgentException taskFailed(String task, Throwable cause) {
        Throwable actual = Protocol.unwrap(cause);
        String message = actual.getMessage() != null ? actual.getMessage() : actual.getClass().getName();
        return new AgentException(ErrorType.TASK_FAILED, "task " + task + " failed: " + message, actual);
    }
}
