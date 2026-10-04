package party.morino.fukurou.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParseException;
import java.util.logging.Logger;
import party.morino.fukurou.agent.api.TaskContext;

/** execute の 1 回の呼び出しの {@link TaskContext}。 */
final class TaskContextImpl implements TaskContext {
    private final String task;
    private final JsonElement args;
    private final Logger logger;

    TaskContextImpl(String task, JsonElement args, Logger logger) {
        this.task = task;
        this.args = args == null ? JsonNull.INSTANCE : args;
        this.logger = logger;
    }

    @Override
    public String task() {
        return task;
    }

    @Override
    public String argsJson() {
        return Protocol.GSON.toJson(args);
    }

    @Override
    public <T> T args(Class<T> type) {
        if (args.isJsonNull()) return null;
        try {
            return Protocol.GSON.fromJson(args, type);
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            throw new IllegalArgumentException("cannot read args of task " + task + " as " + type.getName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void log(String message) {
        logger.info("[" + task + "] " + message);
    }
}
