package party.morino.fukurou.agent.api;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * タスクの static な登録簿（v3 設計 §1.5）。
 *
 * <p>このクラスはエージェントの jar から読まれ、コンパニオンプラグインは {@code depend: [FukurouAgent]} で同じクラスを見る。
 * API のクラスをコンパニオンプラグインに shade してはいけない（別の登録簿になってしまう）。
 * どのスレッドから呼んでもよい。
 */
public final class FukurouTasks {
    /** タスク名の形。英数字で始まり、英数字と {@code _ . : -} だけを使う。 */
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.:-]*$");

    /** 登録済みのタスク。 */
    private static final Map<String, FukurouTask> TASKS = new ConcurrentHashMap<>();

    private FukurouTasks() {}

    /**
     * タスクを登録する。
     *
     * @param name タスク名（{@code ^[A-Za-z0-9][A-Za-z0-9_.:-]*$}）
     * @param task 処理
     * @throws IllegalArgumentException 名前の形が違うとき
     * @throws IllegalStateException 同じ名前が既に登録されているとき
     * @throws NullPointerException name か task が null のとき
     */
    public static void register(String name, FukurouTask task) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(task, "task");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid task name: \"" + name + "\" (must match " + NAME.pattern() + ")");
        }
        if (TASKS.putIfAbsent(name, task) != null) {
            throw new IllegalStateException("task already registered: " + name);
        }
    }

    /**
     * タスクの登録を外す。プラグインの無効化のときに呼ぶ。
     *
     * @param name タスク名
     * @return 登録されていて外したら true
     */
    public static boolean unregister(String name) {
        if (name == null) return false;
        return TASKS.remove(name) != null;
    }

    /**
     * 登録済みのタスク名を返す。
     *
     * @return 名前の順に並べた、変更できない集合（呼んだ時点の写し）
     */
    public static SortedSet<String> names() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(TASKS.keySet()));
    }

    /**
     * 名前でタスクを探す。
     *
     * @param name タスク名
     * @return タスク。登録されていなければ null
     */
    public static FukurouTask find(String name) {
        if (name == null) return null;
        return TASKS.get(name);
    }
}
