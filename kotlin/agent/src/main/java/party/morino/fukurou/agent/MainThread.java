package party.morino.fukurou.agent;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * サーバーのメインスレッドで処理を実行し、結果を待つ（v3 設計 §1.2）。
 *
 * <p>callSyncMethod と同じことを runTask と CompletableFuture でする（callSyncMethod の Future は Error を拾わないので、
 * タスクが投げた AssertionError を確実に返すため）。待ちが切れたら、まだ始まっていない処理は実行しない。
 */
final class MainThread {
    private final Plugin plugin;

    MainThread(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * body をメインスレッドで実行して結果を返す。
     *
     * @param body 実行する処理。{@link AgentException} を投げればそのまま応答になる
     * @param timeoutMs 待つ時間
     * @param onFailure body が AgentException 以外を投げたときの応答の作り方
     * @throws AgentException timeoutMs 以内に終わらない（timeout）、body の失敗、エージェントが無効（internal）
     */
    <T> T call(Callable<T> body, long timeoutMs, Function<Throwable, AgentException> onFailure) throws AgentException {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable runnable = () -> {
            // 待ちが切れた後に始まったものは実行しない
            if (future.isDone()) return;
            try {
                future.complete(body.call());
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        };
        BukkitTask task = null;
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            try {
                task = Bukkit.getScheduler().runTask(plugin, runnable);
            } catch (RuntimeException e) {
                // プラグインの無効化の途中など
                throw new AgentException(ErrorType.INTERNAL, "cannot schedule on the main thread: " + e, e);
            }
        }
        try {
            return future.get(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(false);
            if (task != null) task.cancel();
            throw new AgentException(ErrorType.TIMEOUT, "not executed on the main thread within " + timeoutMs + " ms");
        } catch (ExecutionException e) {
            Throwable cause = Protocol.unwrap(e);
            if (cause instanceof AgentException) throw (AgentException) cause;
            throw onFailure.apply(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(false);
            if (task != null) task.cancel();
            throw new AgentException(ErrorType.INTERNAL, "interrupted while waiting for the main thread");
        }
    }
}
