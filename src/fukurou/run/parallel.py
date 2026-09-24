"""計画したステップ列をブロックに分け、parallel ブロックをレーンごとのスレッドで実行する。

計画（PlannedStep の列）では、同じ parallel.block を持つステップが計画順で連続している。
ここではその連続をブロックにまとめ、レーンごとに 1 スレッドを立てて、レーンの中のステップは順に実行する。
ブロックは全レーンが終わるまで待つ。どれかのレーンが失敗しても、走っているレーンは最後まで進めて
それぞれの結果を記録する（失敗の次以降のステップは skipped のまま）。

テストの期限（timeout）がブロックの途中で来たら stop イベントを立ててレーンを止める。
待ちのあるステップ（wait / wait_for_log / ウィンドウ探し / 撮影の待ち）は協調的に抜けるが、
外部プロセス（xdotool・RCON）の中にいるレーンはその処理の上限時間まで待ってから戻るので、
猶予（GRACE_SECONDS）を置いて合流し、それでも残ったレーンは警告を出して置き去りにする
（デーモンスレッドなので run の終了は妨げない）。置き去りにしたレーンが操作していたプレイヤーは
runner.stranded に入れ、失敗時の撮影で触らず、次のテストの前にクライアントを起動し直す。
"""

from dataclasses import dataclass, field
import logging
import threading
import time

from fukurou.result.recorder import TestRecorder
from fukurou.run.step_executor import ServerDied, StepExecutor
from fukurou.scenario import PlannedStep, PlayerAction

logger = logging.getLogger(__name__)

# stop を立ててからレーンの合流を待つ時間。xdotool（15 秒）や RCON のソケット（接続 10 秒 + 応答 10 秒）より長く取る。
# 撮影の待ちとウィンドウ探しは stop を見て抜けるので、これより長く掛からない
GRACE_SECONDS = 30.0
# レーンのスレッド名の接頭辞。置き去りにしたレーンが生きているかを外から確かめるのに使う
LANE_THREAD_PREFIX = "lane-"


@dataclass
class Lane:
    """parallel ブロックの 1 本のレーン（子）。steps は (計画順の index, ステップ) を実行順に並べたもの。"""

    lane: int
    steps: list[tuple[int, PlannedStep]]
    # 実行中のステップの index。停止の猶予を過ぎても生きているレーンを timeout で記録するために使う
    current: int | None = None
    # レーンの中で起きた、テストの外へ上げるべき例外（ServerDied / KeyboardInterrupt / ハーネスの不具合）
    error: BaseException | None = None
    thread: threading.Thread | None = None

    @property
    def all_skipped(self) -> bool:
        return all(planned.skip_reason is not None for _, planned in self.steps)


@dataclass
class Block:
    """計画順で連続した parallel ブロック、または逐次の 1 ステップ（parallel が None、レーン 1 本）。"""

    parallel: int | None
    lanes: list[Lane] = field(default_factory=list)

    @property
    def first_index(self) -> int:
        return self.lanes[0].steps[0][0]

    @property
    def all_skipped(self) -> bool:
        return all(lane.all_skipped for lane in self.lanes)


def plan_blocks(steps: list[PlannedStep]) -> list[Block]:
    """計画したステップ列を、逐次のステップと parallel ブロックの列にまとめる（純粋関数）。"""
    blocks: list[Block] = []
    for index, planned in enumerate(steps):
        position = planned.parallel
        if position is None:
            blocks.append(Block(parallel=None, lanes=[Lane(lane=0, steps=[(index, planned)])]))
            continue
        if not blocks or blocks[-1].parallel != position.block:
            blocks.append(Block(parallel=position.block))
        block = blocks[-1]
        if not block.lanes or block.lanes[-1].lane != position.lane:
            # 計画ではレーンのステップも連続している（レーン 0 → レーン 1 …）。飛び飛びなら同じレーンに 2 スレッド立ててしまう
            if any(lane.lane == position.lane for lane in block.lanes):
                raise ValueError(f"step {index}: lane {position.lane} of parallel block {position.block} is not contiguous in the plan")
            block.lanes.append(Lane(lane=position.lane, steps=[]))
        block.lanes[-1].steps.append((index, planned))
    return blocks


def run_parallel_block(block: Block, executor: StepExecutor, deadline: float, grace: float = GRACE_SECONDS) -> None:
    """parallel ブロックをレーンごとのスレッドで実行し、全レーンが終わるまで待つ。

    deadline はテストの期限（time.monotonic() の値）。過ぎたらレーンを止め、打ち切ったステップと
    テストを timeout で記録する。レーンの中で起きた ServerDied / KeyboardInterrupt は、他のレーンが
    終わってからこのスレッドで送出し直す。
    """
    recorder = executor.recorder
    for lane in block.lanes:
        if lane.all_skipped:
            # 参加しないプレイヤー向けのレーン（fixture の parallel など）はスレッドを立てずに理由付きで記録する
            for index, planned in lane.steps:
                recorder.step_skipped(index, planned.skip_reason)
            continue
        lane.thread = threading.Thread(
            target=_run_lane, args=(lane, executor), name=f"{LANE_THREAD_PREFIX}{block.parallel}-{lane.lane}", daemon=True
        )
    running = [lane for lane in block.lanes if lane.thread is not None]
    logger.info("parallel block %d: starting %d lanes", block.parallel, len(running))
    # 起動したレーンだけを合流の対象にする（start() の途中で中断されると、未起動のスレッドは join できない）
    started: list[Lane] = []
    try:
        for lane in running:
            lane.thread.start()
            started.append(lane)
        for lane in started:
            lane.thread.join(max(0.0, deadline - time.monotonic()))
        if any(lane.thread.is_alive() for lane in started):
            _stop_lanes(block, started, executor, recorder, grace)
    except KeyboardInterrupt:
        # Ctrl+C / SIGTERM はメインスレッドに届く（起動の途中でも）。レーンを止めてから中断を上げる（テストは skipped になる）
        executor.cancel("timeout", "interrupted")
        end = time.monotonic() + grace
        for lane in started:
            lane.thread.join(max(0.0, end - time.monotonic()))
        raise
    _reraise(started)


def _run_lane(lane: Lane, executor: StepExecutor) -> None:
    """レーンのステップを順に実行する。最初の失敗で止め、残りは skipped のままにする。"""
    try:
        for index, planned in lane.steps:
            if executor.stop.is_set():
                # 期限切れで止められた。まだ始めていないステップは skipped のまま
                break
            lane.current = index
            try:
                if not executor.run(index, planned):
                    break
            finally:
                lane.current = None
    except ServerDied as error:
        # 記録は run() が済ませている。待っている他のレーンは打ち切り、メインスレッドで上げ直す
        lane.error = error
        executor.cancel(None, f"cancelled: {error}")
    except KeyboardInterrupt as error:
        # レーンの中に届いた中断。兄弟の打ち切りは期限切れではなく中断として記録し、メインスレッドで上げ直す
        lane.error = error
        executor.cancel(None, "cancelled: interrupted")
    except BaseException as error:  # noqa: BLE001 - スレッドの中で握りつぶさず、メインスレッドで上げ直す
        # ハーネスの不具合。テストは放棄されるが、兄弟の記録とログには期限切れではなく本当の原因を残す
        lane.error = error
        executor.cancel(None, f"cancelled: harness error in lane {lane.lane}: {type(error).__name__}: {error}")


def _stop_lanes(block: Block, running: list[Lane], executor: StepExecutor, recorder: TestRecorder, grace: float) -> None:
    """期限切れ: レーンを止め、猶予の後も生きているレーンの実行中のステップを timeout で記録する。"""
    logger.error("parallel block %d: %s; stopping the lanes", block.parallel, executor.timeout_message())
    executor.cancel("timeout", executor.timeout_message())
    # 猶予は全レーンで共有する（レーンごとに待つと、置き去りにするレーンの数だけ長くなる）
    end = time.monotonic() + grace
    for lane in running:
        lane.thread.join(max(0.0, end - time.monotonic()))
    for lane in running:
        if not lane.thread.is_alive():
            continue
        # 外部プロセスの中で止まっているレーン。ステップの記録はこのスレッドで済ませ、レーンは置き去りにする
        logger.warning("parallel block %d lane %d is still running after %gs; leaving it behind", block.parallel, lane.lane, grace)
        current = lane.current
        if current is None:
            continue
        recorder.step_failed(current, None, executor.timeout_message(current), phase="timeout")
        # そのレーンがまだ操作しているかもしれないプレイヤー。失敗時の撮影で触らず、次のテストの前に起動し直す
        # （起動し直すとクライアントとディスプレイが止まり、置き去りにしたレーンの操作もそこで終わる）
        step = dict(lane.steps)[current].step
        if isinstance(step, PlayerAction):
            executor.runner.stranded.add(step.on)
            logger.warning("%s: the client may still be driven by the abandoned lane; it will be relaunched", step.on)
    # どのレーンも打ち切りを記録できなかった（ちょうど終わった）場合のために、テストの失敗を確実に残す
    recorder.fail("timeout", f"{executor.timeout_message()} during parallel block {block.parallel}")


def live_lanes() -> list[str]:
    """まだ動いているレーンのスレッド名。置き去りにしたレーンがテストの放棄後も残っているかをログに出すのに使う。"""
    return sorted(thread.name for thread in threading.enumerate() if thread.name.startswith(LANE_THREAD_PREFIX))


def _reraise(running: list[Lane]) -> None:
    """レーンの中で起きた例外を、サーバーの死亡 → 中断 → その他の順で送出し直す。"""
    errors = [lane.error for lane in running if lane.error is not None]
    for kind in (ServerDied, KeyboardInterrupt):
        for error in errors:
            if isinstance(error, kind):
                raise error
    if errors:
        raise errors[0]
