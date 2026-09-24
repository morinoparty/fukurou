"""fukurou が1バージョン分の実行ごとに書き出す result.json の契約（schemaVersion 1）。

fukurou/ui はこのファイルだけを頼りに表示を組み立てる。互換性のルール:
- フィールドの追加は後方互換として扱い、ビューアは知らないフィールドを無視する
- フィールドの削除・意味の変更は schemaVersion を上げ、ランナーと ui を同時に更新する
- パスはすべて artifact のルートからの相対パス（".." や絶対パスは使わない）
"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

SCHEMA_VERSION = 1

# passed: 全ステップ成功 / failed: シナリオのステップが失敗 / error: 起動やダウンロード等のインフラ失敗
RunStatus = Literal["passed", "failed", "error"]
StepStatus = Literal["passed", "failed", "skipped"]
# どの段階で失敗したか。ビューアで失敗の原因をひと目で分かるようにする
FailurePhase = Literal["setup", "server-start", "client-join", "scenario", "teardown"]


class ContractModel(BaseModel):
    """契約モデル共通の設定。書き出し時は camelCase のキーを使う。"""

    model_config = ConfigDict(populate_by_name=True, serialize_by_alias=True)


class FukurouInfo(ContractModel):
    version: str
    portablemc: str


class MinecraftInfo(ContractModel):
    version: str
    server: Literal["paper"] = "paper"
    build: int | None = None
    channel: str | None = None


class JavaInfo(ContractModel):
    # サーバーを起動した Java の major 番号。クライアントは PortableMC が選ぶ公式ランタイム
    server: int | None = None


class ScenarioInfo(ContractModel):
    name: str
    # "file:<path>" または "inline"
    source: str
    sha256: str


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


class PlayerInfo(ContractModel):
    name: str
    op: bool = False
    joined: bool = False


class StepResult(ContractModel):
    index: int
    # "server" / プレイヤー名 / None（共通アクション）
    on: str | None = None
    action: str
    # 一覧表示用の短い説明（コマンド・チャット本文・スクリーンショット名など）
    label: str
    status: StepStatus
    duration_ms: int | None = Field(default=None, alias="durationMs")
    error: str | None = None
    # screenshot アクションのときだけ、保存先の相対パス
    screenshot: str | None = None


class Failure(ContractModel):
    phase: FailurePhase
    message: str
    step_index: int | None = Field(default=None, alias="stepIndex")


class ScreenshotInfo(ContractModel):
    player: str
    name: str
    # 例: screenshots/Alice/stamp.png
    path: str
    width: int
    height: int
    step_index: int | None = Field(default=None, alias="stepIndex")


class LogInfo(ContractModel):
    # harness: fukurou 自身 / server: サーバーの latest.log / client: プレイヤーのクライアントの latest.log / crash: クラッシュレポート
    kind: Literal["harness", "server", "client", "crash"]
    path: str
    player: str | None = None


class CiInfo(ContractModel):
    """GitHub Actions 上で実行したときだけ埋める。ローカル実行では null。"""

    repository: str | None = None
    sha: str | None = None
    ref: str | None = None
    run_id: str | None = Field(default=None, alias="runId")
    run_attempt: str | None = Field(default=None, alias="runAttempt")
    server_url: str | None = Field(default=None, alias="serverUrl")


class ResultV1(ContractModel):
    """result.json のルート。"""

    schema_version: Literal[1] = Field(default=SCHEMA_VERSION, alias="schemaVersion")
    # "<server>-<minecraft version>"（例: paper-1.21.11）。artifact 名の末尾と一致させる
    id: str
    status: RunStatus
    fukurou: FukurouInfo
    minecraft: MinecraftInfo
    java: JavaInfo = JavaInfo()
    scenario: ScenarioInfo | None = None
    plugins: list[PluginInfo] = []
    players: list[PlayerInfo] = []
    steps: list[StepResult] = []
    failure: Failure | None = None
    screenshots: list[ScreenshotInfo] = []
    logs: list[LogInfo] = []
    started_at: str = Field(alias="startedAt")
    finished_at: str | None = Field(default=None, alias="finishedAt")
    duration_ms: int | None = Field(default=None, alias="durationMs")
    ci: CiInfo | None = None
