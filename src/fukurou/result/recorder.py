"""実行の進行に合わせて result.json（schemaVersion 2）の内容を組み立て、アトミックに書き出す。

RunRecorder は run 全体（セッション・プレイヤー・run の失敗）、TestRecorder はテスト 1 件を記録する。
どちらもプロセスには触れない受け身のデータで、SuiteRun が発見直後・各テストの後・後片付けの度に write() する。
"""

from datetime import UTC, datetime
import logging
import os
from pathlib import Path
import threading
import time
from typing import TYPE_CHECKING

from fukurou import __version__
from fukurou.result.ci import ci_from_env
from fukurou.result.model import (
    STEP_FAILURE_PHASES,
    FukurouInfo,
    JavaInfo,
    LogInfo,
    LogRange,
    MinecraftInfo,
    ParallelInfo,
    PlayerInfo,
    PluginInfo,
    RepeatInfo,
    ResetInfo,
    ResultV2,
    RunFailure,
    RunFailurePhase,
    RunStatus,
    ScreenshotInfo,
    SelectionInfo,
    SessionInfo,
    SessionKind,
    StepResult,
    SuiteInfo,
    TestFailure,
    TestFailurePhase,
    TestPlayer,
    TestResult,
    TestStatus,
    derive_run_status,
    summarize_tests,
)
from fukurou.result.steps import step_label, step_target
from fukurou.runner.portablemc import PORTABLEMC_VERSION

if TYPE_CHECKING:
    from fukurou.scenario import PlannedStep, TestSpec

logger = logging.getLogger(__name__)

SERVER_KIND = "paper"
# 発見直後のスタブで全テストに付ける理由。ジョブが途中で消えても「走らなかった」と分かる
NOT_RUN = "not run"


def utc_timestamp(moment: datetime) -> str:
    """契約どおり秒精度の UTC（末尾 Z）で表す。"""
    return moment.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def now_timestamp() -> str:
    return utc_timestamp(datetime.now(UTC))


def step_timestamp(moment: datetime) -> str:
    """ステップの開始・終了時刻はミリ秒精度で表す。parallel のステップの重なりは秒精度では見えないため。"""
    return moment.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%S.") + f"{moment.microsecond // 1000:03d}Z"


def now_step_timestamp() -> str:
    return step_timestamp(datetime.now(UTC))


class TestRecorder:
    """テスト 1 件の結果。start() までは skipped（NOT_RUN）で、実行した分だけ内容が埋まる。"""

    # pytest がテストクラスとして収集しないようにする
    __test__ = False

    def __init__(self, spec: "TestSpec"):
        self.spec = spec
        self.skip_reason: str | None = NOT_RUN
        self.session: int | None = None
        self.reset: ResetInfo | None = None
        self.failure: TestFailure | None = None
        self.screenshots: list[ScreenshotInfo] = []
        self.log_ranges: dict[str, LogRange] | None = None
        self.started_at: str | None = None
        self.duration_ms: int | None = None
        self._started_monotonic: float | None = None
        self._finished = False
        # parallel ブロックのレーンは別スレッドから記録するため、ステップと失敗の更新はこのロックで直列にする
        self._lock = threading.Lock()
        # 全ステップを skipped として用意し、実行した分だけ置き換える（fixture 名は phase と対にする）
        self.steps = [_initial_step(index, planned) for index, planned in enumerate(spec.steps)]

    @property
    def id(self) -> str:
        return self.spec.id

    @property
    def status(self) -> TestStatus:
        """skipped（理由あり）/ failed（ステップの失敗）/ error（ハーネス側の失敗）/ passed。"""
        if self.skip_reason is not None:
            return "skipped"
        if self.failure is not None:
            return "failed" if self.failure.phase in STEP_FAILURE_PHASES else "error"
        # finish() 前に書き出されることは無いはずだが、もしあれば成功とは見なさない
        return "passed" if self._finished else "error"

    @property
    def elapsed_seconds(self) -> float:
        """start() からの経過秒数。ソフトなタイムアウトの判定に使う。"""
        return 0.0 if self._started_monotonic is None else time.monotonic() - self._started_monotonic

    def start(self, session: int) -> None:
        """テストを開始する。以後は skipped ではなくなる。"""
        self.skip_reason = None
        self.session = session
        self.started_at = now_timestamp()
        self._started_monotonic = time.monotonic()

    def set_reset(self, reset: ResetInfo) -> None:
        self.reset = reset
        if reset.error is not None:
            self.fail("reset", reset.error)

    def step_started(self, index: int) -> None:
        """ステップの実行を始めた時刻を記録する。parallel のステップはこれで重なりが分かる。"""
        self._update_step(index, started_at=now_step_timestamp())

    def step_passed(self, index: int, duration_ms: int, screenshot: str | None = None) -> None:
        # 判定と更新は 1 つの臨界区間で行う。間にロックを手放すと、メインスレッドが期限切れで failed と記録した
        # 直後にこのレーンが passed で上書きし、timeout のエラーを持った passed のステップができてしまう
        with self._lock:
            if self.steps[index].status == "failed":
                # 期限切れで timeout と記録した後に、置き去りにしたレーンが遅れて終わった。ハーネスの判断を優先し、
                # そのステップが遅れて撮ったスクリーンショットも載せない（failed のステップを指す項目にしない）
                logger.warning("step %d passed after it was recorded as failed; keeping the failure", index)
                self.screenshots = [shot for shot in self.screenshots if not (shot.step_index == index and shot.path == screenshot)]
                return
            # error は明示的に消し、以前の記録が残らないようにする
            self._update_step_locked(
                index, status="passed", duration_ms=duration_ms, error=None, screenshot=screenshot, finished_at=now_step_timestamp()
            )

    def step_failed(self, index: int, duration_ms: int | None, error: str, phase: TestFailurePhase | None = None) -> None:
        """ステップの失敗。失敗の段階は既定ではそのステップの層（beforeEach / fixture / test → scenario）。

        クライアントの死亡のようにハーネス側の原因なら phase を指定し、テストを error にする。
        parallel で複数のレーンが失敗した場合は、先に（時間順で）失敗したステップが failure になる。
        """
        with self._lock:
            if self._finished:
                # 期限切れの後に遅れて終わったレーンの記録。書き出し済みの結果は変えない
                logger.warning("step %d finished after the test was closed; ignoring its failure", index)
                return
            self.steps[index] = self.steps[index].model_copy(
                update={"status": "failed", "duration_ms": duration_ms, "error": error, "finished_at": now_step_timestamp()}
            )
            if phase is None:
                step_phase = self.steps[index].phase
                phase = "scenario" if step_phase == "test" else step_phase
            self._fail(phase, error, step_index=index)

    def step_skipped(self, index: int, reason: str | None = None) -> None:
        self._update_step(index, status="skipped", error=reason)

    def add_screenshot(self, info: ScreenshotInfo) -> None:
        with self._lock:
            if self._finished:
                logger.warning("screenshot %s/%s was taken after the test was closed; ignoring it", info.player, info.name)
                return
            self.screenshots.append(info)

    def set_log_ranges(self, ranges: dict[str, LogRange]) -> None:
        self.log_ranges = dict(ranges)

    def skip(self, reason: str) -> None:
        """走らなかった（または走り切れなかった）テストにする。契約どおり実行の痕跡は残さない。

        finish() と同じく終端の状態なので、以後の遅れたレーンの記録（中断で置き去りにしたレーンの
        スクリーンショットや失敗）は無視する。skip() の後に start() されることは無い。
        """
        with self._lock:
            self._finished = True
            self.skip_reason = reason
            self.session = None
            self.reset = None
            self.failure = None
            self.screenshots = []
            self.log_ranges = None
            self.started_at = None
            self.duration_ms = None
            # ブロック内の位置（parallel / repeat）は計画の事実なので残し、実行の痕跡だけを消す
            cleared = {"status": "skipped", "duration_ms": None, "error": None, "screenshot": None, "started_at": None, "finished_at": None}
            self.steps = [step.model_copy(update=cleared) for step in self.steps]

    def fail(self, phase: TestFailurePhase, message: str, step_index: int | None = None) -> None:
        """最初の失敗だけを記録する。後続の失敗は最初の失敗の結果であることが多いため。"""
        with self._lock:
            self._fail(phase, message, step_index)

    def finish(self) -> None:
        """実行を終える。失敗が無ければ passed になる。以後の遅れたステップの記録は無視する。"""
        with self._lock:
            self._finished = True
        if self._started_monotonic is not None:
            self.duration_ms = int((time.monotonic() - self._started_monotonic) * 1000)

    def build(self) -> TestResult:
        spec = self.spec
        return TestResult(
            id=spec.id,
            name=spec.name,
            order=spec.order,
            source=spec.source,
            sha256=spec.sha256,
            tags=list(spec.tags),
            isolation=spec.isolation,
            timeout=spec.timeout,
            versions=spec.versions,
            session=self.session,
            status=self.status,
            skip_reason=self.skip_reason,
            players=[TestPlayer(name=player.name, op=player.op) for player in spec.players],
            reset=self.reset,
            # 走らなかったテストは契約どおり steps を空にする（展開済みのステップは start() 後に使う）
            steps=[] if self.skip_reason is not None else list(self.steps),
            failure=self.failure,
            screenshots=list(self.screenshots),
            log_ranges=self.log_ranges,
            started_at=self.started_at,
            duration_ms=self.duration_ms,
        )

    def _fail(self, phase: TestFailurePhase, message: str, step_index: int | None) -> None:
        # 呼び出し側が _lock を持っている前提
        if self.failure is None:
            self.failure = TestFailure(phase=phase, message=message, step_index=step_index)

    def _update_step(self, index: int, **changes) -> None:
        with self._lock:
            self._update_step_locked(index, **changes)

    def _update_step_locked(self, index: int, **changes) -> None:
        # 呼び出し側が _lock を持っている前提
        if self._finished:
            logger.warning("step %d was updated after the test was closed; ignoring %s", index, sorted(changes))
            return
        self.steps[index] = self.steps[index].model_copy(update=changes)


def _initial_step(index: int, planned: "PlannedStep") -> StepResult:
    """計画したステップの実行前の記録（skipped）。ブロック内の位置は計画からそのまま写す。"""
    return StepResult(
        index=index,
        phase=planned.phase,
        fixture=planned.fixture,
        on=step_target(planned.step),
        action=planned.step.action,
        label=step_label(planned.step),
        status="skipped",
        parallel=None if planned.parallel is None else ParallelInfo(block=planned.parallel.block, lane=planned.parallel.lane),
        # 契約では空の一覧は不可なので、repeat の外は None にする
        repeat=[RepeatInfo(block=r.block, iteration=r.iteration, of=r.of) for r in planned.repeat] or None,
    )


class RunRecorder:
    """1 バージョン分の実行の結果。途中で失敗しても、その時点までの内容で result.json を書ける。"""

    def __init__(self, minecraft_version: str, env: dict[str, str] | None = None):
        self.started_at = datetime.now(UTC)
        self._started_monotonic = time.monotonic()
        self.minecraft = MinecraftInfo(version=minecraft_version, server=SERVER_KIND)
        self.java = JavaInfo()
        self.plugins: list[PluginInfo] = []
        self.suite: SuiteInfo | None = None
        self.selection = SelectionInfo()
        self.players: list[PlayerInfo] = []
        self.sessions: list[SessionInfo] = []
        self.tests: dict[str, TestRecorder] = {}
        self.failure: RunFailure | None = None
        self.logs: list[LogInfo] = []
        self.ci = ci_from_env(os.environ if env is None else env)

    @property
    def status(self) -> RunStatus:
        return derive_run_status(self.failure, [test.build() for test in self.tests.values()])

    def register_tests(self, specs: list["TestSpec"]) -> None:
        """発見したテストを実行順に登録する。全テストが skipped（not run）のスタブになる。"""
        self.tests = {spec.id: TestRecorder(spec) for spec in specs}

    def test(self, test_id: str) -> TestRecorder:
        return self.tests[test_id]

    def set_players(self, names: list[str]) -> None:
        """セッションに参加させるプレイヤー（全テストの和集合、参加順）。"""
        self.players = [PlayerInfo(name=name) for name in names]

    def mark_joined(self, name: str) -> None:
        for player in self.players:
            if player.name == name:
                player.joined = True

    def set_plugin_enabled(self, enabled: dict[str, bool]) -> None:
        """組み込みのプラグイン確認の結果を、名前の分かるプラグインに反映する。"""
        for plugin in self.plugins:
            if plugin.name in enabled:
                plugin.enabled = enabled[plugin.name]

    def add_session(self, kind: SessionKind) -> SessionInfo:
        """サーバーを起動したセッションを追加し、その情報を返す（index は追加順）。"""
        session = SessionInfo(index=len(self.sessions), kind=kind, started_at=now_timestamp())
        self.sessions.append(session)
        return session

    def add_session_log(self, index: int, info: LogInfo) -> None:
        """セッションで回収したログを 1 つ足す（同じパスは 1 回だけ）。"""
        session = self.sessions[index]
        if all(existing.path != info.path for existing in session.logs):
            session.logs.append(info)

    def finish_session(self, index: int, logs: list[LogInfo], failure: str | None = None) -> None:
        """セッションを閉じる。回収したログは、再起動時に回収済みのものへ足す。"""
        session = self.sessions[index]
        session.finished_at = now_timestamp()
        for info in logs:
            self.add_session_log(index, info)
        if failure is not None and session.failure is None:
            session.failure = failure

    def fail(self, phase: RunFailurePhase, message: str) -> None:
        """最初の失敗だけを記録する。後続の失敗は最初の失敗の結果であることが多いため。"""
        if self.failure is None:
            self.failure = RunFailure(phase=phase, message=message)

    def skip_pending(self, reason: str) -> list[str]:
        """まだ走っていない（not run の）テストを理由付きの skipped にし、その id を返す。"""
        pending = [test for test in self.tests.values() if test.skip_reason == NOT_RUN]
        for test in pending:
            test.skip(reason)
        return [test.id for test in pending]

    def build(self) -> ResultV2:
        """現時点の内容から ResultV2 を作る。summary と status は tests から導出する。"""
        tests = [test.build() for test in self.tests.values()]
        return ResultV2(
            id=f"{SERVER_KIND}-{self.minecraft.version}",
            status=derive_run_status(self.failure, tests),
            fukurou=FukurouInfo(version=__version__, portablemc=PORTABLEMC_VERSION),
            minecraft=self.minecraft,
            java=self.java,
            plugins=self.plugins,
            suite=self.suite,
            selection=self.selection,
            players=self.players,
            summary=summarize_tests(tests),
            sessions=self.sessions,
            tests=tests,
            failure=self.failure,
            logs=self.logs,
            started_at=utc_timestamp(self.started_at),
            finished_at=now_timestamp(),
            duration_ms=int((time.monotonic() - self._started_monotonic) * 1000),
            ci=self.ci,
        )

    def write(self, path: Path) -> ResultV2:
        """result.json を書く。途中で中断されても壊れたファイルが残らないよう、一時ファイルから置き換える。"""
        result = self.build()
        write_result(result, path)
        return result


def write_result(result: ResultV2, path: Path) -> None:
    """ResultV2 を camelCase の JSON としてアトミックに書き出す。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(result.model_dump_json(by_alias=True, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, path)
