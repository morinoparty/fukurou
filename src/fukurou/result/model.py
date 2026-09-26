"""fukurou が1バージョン分の実行ごとに書き出す result.json の契約（schemaVersion 2）。

fukurou/ui はこのファイルだけを頼りに表示を組み立てる。互換性のルール:
- フィールドの追加は後方互換として扱い、ビューアは知らないフィールドを無視する
- フィールドの削除・意味の変更は schemaVersion を上げ、ランナーと ui を同時に更新する
- パスはすべて artifact のルートからの相対パス（".." や絶対パスは使わない）

schemaVersion 2 では 1 バージョン = 1 サーバーセッションで複数のテストを実行するため、
ステップ・スクリーンショットはルートから tests[] へ、ログは sessions[] へ移った。
"""

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

SCHEMA_VERSION = 2

# run（バージョン）単位の結果。passed: 全テストが passed / skipped、failed: いずれかのテストが failed / error、
# error: インフラの失敗（run.failure）がテストの実行を妨げた
RunStatus = Literal["passed", "failed", "error"]
# テスト単位の結果。failed はステップの失敗（プラグインのバグ候補）、error はハーネス側の失敗
TestStatus = Literal["passed", "failed", "error", "skipped"]
StepStatus = Literal["passed", "failed", "skipped"]
# run 全体を止めた失敗の段階。これがあると残りのテストは skipped になる
RunFailurePhase = Literal["setup", "server-start", "client-join", "server", "teardown", "interrupted"]
# テスト 1 件の失敗の段階。beforeEach / fixture / scenario はステップの失敗（failed）、それ以外は error
TestFailurePhase = Literal["reset", "beforeEach", "fixture", "scenario", "client", "timeout"]
# ステップがどの層から来たか（スイートの beforeEach / use した fixture / テスト自身の steps）
StepPhase = Literal["beforeEach", "fixture", "test"]
# テストの隔離方法。scenario.suite の Isolation と同じ値だが、契約はシナリオのモデルに依存させない
Isolation = Literal["reset", "fresh-server"]
SessionKind = Literal["initial", "fresh-server"]

# failure.phase から、テストの status が failed（ステップの失敗）になる段階
STEP_FAILURE_PHASES: frozenset[str] = frozenset({"beforeEach", "fixture", "scenario"})


class ContractModel(BaseModel):
    """契約モデル共通の設定。書き出し時は camelCase のキーを使う。"""

    model_config = ConfigDict(populate_by_name=True, serialize_by_alias=True)


class FukurouInfo(ContractModel):
    version: str
    portablemc: str
    # 結果を書いたランナー。JVM 版（fukurou-kotlin）は "kotlin"。Python 版は書かない（None）
    runner: str | None = None


class MinecraftInfo(ContractModel):
    version: str
    server: Literal["paper"] = "paper"
    build: int | None = None
    channel: str | None = None


class JavaInfo(ContractModel):
    # サーバーを起動した Java の major 番号。クライアントは PortableMC が選ぶ公式ランタイム
    server: int | None = None


class PluginInfo(ContractModel):
    file: str
    sha256: str
    name: str | None = None
    version: str | None = None
    # under-test: テスト対象として渡されたプラグイン / dependency: dependencies 入力で取得したプラグイン
    role: Literal["under-test", "dependency"]
    source: str | None = None
    class_file_major: int | None = Field(default=None, alias="classFileMajor")
    # サーバーログで有効化を確認できたか。確認前に失敗した場合は None
    enabled: bool | None = None


class ArenaInfo(ContractModel):
    """リセット時に空気で埋める領域の大きさ（x, z は原点を中心に size、y は地面から height）。"""

    size: int
    height: int


class SuiteInfo(ContractModel):
    """実行に使ったスイートの設定。スイートファイルが無い実行でも既定値で埋める。"""

    # "file:<path>"。--scenario-file / --scenario だけの実行では None
    source: str | None = None
    sha256: str | None = None
    # テストが指定しないときの隔離方法
    isolation: Isolation = "reset"
    # リセット後にクライアントがブロック更新を受け取るまで待つ秒数
    settle: float = 2
    # リセット時に参加プレイヤーへ設定するゲームモード
    gamemode: str = "survival"
    # false はアリーナのリセットを無効にしたことを表す
    arena: ArenaInfo | Literal[False] | None = None


class SelectionInfo(ContractModel):
    """どのテストを選んだか（CLI のフィルタとアクションの入力）。空の選択は実行前に exit 2 になる。"""

    # --test の id / glob。空なら全テスト
    tests: list[str] = []
    # --tag。空ならタグで絞っていない
    tags: list[str] = []
    # --isolation で全テストに強制した隔離方法。None は宣言どおり
    isolation: Isolation | None = None
    fail_fast: bool = Field(default=False, alias="failFast")


class PlayerInfo(ContractModel):
    """セッションに参加させるプレイヤー（全テストの和集合）。op はテストごとなので tests[].players にある。"""

    name: str
    joined: bool = False


class TestPlayer(ContractModel):
    """そのテストの参加プレイヤー。リセット時に op / deop をこの宣言に合わせる。"""

    # pytest がテストクラスとして収集しないようにする
    __test__ = False

    name: str
    op: bool = False


class Summary(ContractModel):
    """テストのステータスごとの件数。"""

    total: int = 0
    passed: int = 0
    failed: int = 0
    error: int = 0
    skipped: int = 0


class LogInfo(ContractModel):
    # harness: fukurou 自身 / server: サーバーのコンソールの記録 / client: プレイヤーのクライアントの latest.log / crash: クラッシュレポート
    kind: Literal["harness", "server", "client", "crash"]
    path: str
    player: str | None = None


class SessionInfo(ContractModel):
    """サーバーの起動 1 回分。initial が 1 つと、fresh-server のテストごとに 1 つ。"""

    index: int
    kind: SessionKind
    started_at: str | None = Field(default=None, alias="startedAt")
    finished_at: str | None = Field(default=None, alias="finishedAt")
    # このセッションに参加したプレイヤー
    players: list[str] = []
    # このセッションで実行したテストの id（実行順）
    tests: list[str] = []
    # 例: logs/sessions/0/server.log, logs/sessions/0/clients/Alice.log, logs/sessions/0/clients/Alice.2.log
    logs: list[LogInfo] = []
    # サーバーがセッションの途中で死んだときのメッセージ
    failure: str | None = None


class ResetInfo(ContractModel):
    """テストの前にハーネスが行ったリセット（ステップではない）。"""

    duration_ms: int | None = Field(default=None, alias="durationMs")
    # 失敗した RCON コマンドとその応答。あればテストは error（phase reset）
    error: str | None = None


class ParallelInfo(ContractModel):
    """parallel ブロックの中のステップの位置。同じ block のステップは同時に実行された。"""

    # テスト内の parallel ブロックの通し番号（0 始まり、計画順。repeat で展開した分は別のブロック）
    block: int
    # ブロックの中の子の番号（0 始まり）。同じ lane のステップは順に実行された
    lane: int


class RepeatInfo(ContractModel):
    """repeat ブロックの何回目のステップか。"""

    # テスト内の repeat ブロックの通し番号（0 始まり、計画順。外側の repeat で展開した分は別のブロック）
    block: int
    # 1 始まりの繰り返し番号
    iteration: int
    # 繰り返しの回数（times）
    of: int


class StepResult(ContractModel):
    # そのテストの steps 配列の添字（failure.stepIndex と screenshots[].stepIndex もこれを指す）
    index: int
    phase: StepPhase = "test"
    # phase が fixture のときだけ fixture の名前
    fixture: str | None = None
    # "server" / プレイヤー名 / None（共通アクション）
    on: str | None = None
    action: str
    # 一覧表示用の短い説明（コマンド・チャット本文・スクリーンショット名など）
    label: str
    status: StepStatus
    duration_ms: int | None = Field(default=None, alias="durationMs")
    # 失敗の理由。skipped のときは「player Bob is not in this test」のような飛ばした理由
    error: str | None = None
    # screenshot アクションのときだけ、保存先の相対パス
    screenshot: str | None = None
    # parallel ブロックの中のステップなら、そのブロックとレーン。それ以外は None
    parallel: ParallelInfo | None = None
    # repeat ブロックの中のステップなら、外側から順に何回目か。それ以外は None（空の一覧は不可: スキーマにも minItems で表す）
    repeat: Annotated[list[RepeatInfo], Field(min_length=1)] | None = None
    # 実行を始めた / 終えた時刻（ISO 8601）。並列のステップは durationMs が重なるので、ビューアはこれで重なりを示す
    started_at: str | None = Field(default=None, alias="startedAt")
    finished_at: str | None = Field(default=None, alias="finishedAt")

    @model_validator(mode="after")
    def _check_fixture(self) -> "StepResult":
        # fixture 名は phase と対応していないと、ビューアの折りたたみの見出しが食い違う
        if (self.phase == "fixture") != (self.fixture is not None):
            raise ValueError("fixture must be set exactly when phase is 'fixture'")
        return self


class ScreenshotInfo(ContractModel):
    player: str
    name: str
    # 例: tests/stamp-thinking-face/screenshots/Alice/after-stamp.png
    path: str
    width: int
    height: int
    step_index: int | None = Field(default=None, alias="stepIndex")


class LogRange(ContractModel):
    """ログファイルのうちテスト 1 件の間に書かれた行。1 始まりで両端を含む。"""

    # from は Python の予約語なので属性名を変え、JSON のキーは from のままにする
    from_line: int = Field(alias="from")
    to: int


class RunFailure(ContractModel):
    """run 全体を止めたインフラの失敗。"""

    phase: RunFailurePhase
    message: str


class TestFailure(ContractModel):
    """テスト 1 件の失敗。stepIndex はそのテストの steps の添字（ステップに結び付かなければ None）。"""

    __test__ = False

    phase: TestFailurePhase
    message: str
    step_index: int | None = Field(default=None, alias="stepIndex")


class TestResult(ContractModel):
    """テスト（シナリオファイル）1 件の結果。"""

    __test__ = False

    # ファイル名の stem（インラインは "inline"）。tests/<id>/ のパスとビューアのルートに使う
    id: str
    name: str
    # 実行順（reset のテストを宣言順 → fresh-server のテストを宣言順）
    order: int
    # "file:<path>" または "inline"
    source: str
    sha256: str
    tags: list[str] = []
    isolation: Isolation = "reset"
    # 秒。超えたらテストは error（phase timeout）
    timeout: float = 600
    # versions: の制約（例: "1.21.9-"）。None は全バージョン
    versions: str | None = None
    # 実行したセッションの index。走らなかったテストは None
    session: int | None = None
    status: TestStatus
    # skipped のときの理由（"versions: ...", "fail-fast", "not run", "server died during <id>", "client relaunch failed: <player>"）
    skip_reason: str | None = Field(default=None, alias="skipReason")
    players: list[TestPlayer] = []
    reset: ResetInfo | None = None
    steps: list[StepResult] = []
    failure: TestFailure | None = None
    screenshots: list[ScreenshotInfo] = []
    # セッションで回収したログファイルのパス → このテストの間の行。テストが走らなければ None
    log_ranges: dict[str, LogRange] | None = Field(default=None, alias="logRanges")
    started_at: str | None = Field(default=None, alias="startedAt")
    duration_ms: int | None = Field(default=None, alias="durationMs")

    @model_validator(mode="after")
    def _check_skip_reason(self) -> "TestResult":
        # 飛ばした理由が無いと、ビューアで「なぜ走らなかったか」を示せない
        if self.status == "skipped" and not self.skip_reason:
            raise ValueError("skipReason is required when status is 'skipped'")
        return self


class CiInfo(ContractModel):
    """GitHub Actions 上で実行したときだけ埋める。ローカル実行では null。"""

    repository: str | None = None
    sha: str | None = None
    ref: str | None = None
    run_id: str | None = Field(default=None, alias="runId")
    run_attempt: str | None = Field(default=None, alias="runAttempt")
    server_url: str | None = Field(default=None, alias="serverUrl")


class ResultV2(ContractModel):
    """result.json のルート。"""

    schema_version: Literal[2] = Field(default=SCHEMA_VERSION, alias="schemaVersion")
    # "<server>-<minecraft version>[-<label>]"（例: paper-1.21.11）。artifact 名の末尾と一致させる
    id: str
    # 1 つのジョブが同じバージョンで複数の result を書くとき（fukurou-kotlin）のサーバーのラベル。
    # そのとき id は <server>-<version>-<label> になる。Python 版は書かない（None）
    label: str | None = None
    status: RunStatus
    fukurou: FukurouInfo
    minecraft: MinecraftInfo
    java: JavaInfo = JavaInfo()
    plugins: list[PluginInfo] = []
    # テストの発見より前に失敗した場合は None
    suite: SuiteInfo | None = None
    selection: SelectionInfo = SelectionInfo()
    players: list[PlayerInfo] = []
    summary: Summary = Summary()
    sessions: list[SessionInfo] = []
    tests: list[TestResult] = []
    failure: RunFailure | None = None
    # ハーネス自身のログなど、セッションに属さないログ
    logs: list[LogInfo] = []
    started_at: str = Field(alias="startedAt")
    finished_at: str | None = Field(default=None, alias="finishedAt")
    duration_ms: int | None = Field(default=None, alias="durationMs")
    ci: CiInfo | None = None


def summarize_tests(tests: list[TestResult]) -> Summary:
    """テストのステータスごとの件数を数える（result.summary に入れる値）。"""
    summary = Summary(total=len(tests))
    for test in tests:
        setattr(summary, test.status, getattr(summary, test.status) + 1)
    return summary


def derive_run_status(failure: RunFailure | None, tests: list[TestResult]) -> RunStatus:
    """run の status を run.failure と tests から導出する。

    インフラの失敗があれば error、無ければいずれかのテストが failed / error なら failed、
    それ以外（全テストが passed / skipped）なら passed。
    """
    if failure is not None:
        return "error"
    if any(test.status in ("failed", "error") for test in tests):
        return "failed"
    return "passed"
