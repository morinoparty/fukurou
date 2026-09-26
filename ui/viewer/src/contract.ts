// docs/design/v2-multi-test.md §5 の result.json（schemaVersion 2）と manifest.json（schemaVersion 2）を TypeScript に写した型。
// ランナー・build_manifest.py とビューアの唯一のインターフェースなので、フィールド名・null 許容は契約どおりに保つ。
// 契約上「フィールド追加は後方互換」なので、ここに無いキーが来ても無視するだけでよい。

/** run（1 バージョン）全体の結果。error はインフラ失敗（run.failure あり）、failed はいずれかのテストが failed / error */
export type RunStatus = "passed" | "failed" | "error";

/** テスト単位の結果。skipped は versions: / fail-fast / 先行のインフラ失敗などで実行しなかったもの（skipReason 必須） */
export type TestStatus = "passed" | "failed" | "error" | "skipped";

/** ステップ単位の結果。失敗後に実行されなかったステップは skipped になる */
export type StepStatus = "passed" | "failed" | "skipped";

/** ステップがどの層に属するか。beforeEach と fixture はスイートの共通ステップ、test はテスト自身の steps */
export type StepPhase = "beforeEach" | "fixture" | "test";

/** run 全体を止めたインフラ失敗の段階 */
export type RunFailurePhase = "setup" | "server-start" | "client-join" | "server" | "teardown" | "interrupted";

/** テストが失敗した段階。reset / client / timeout はテストの status が error、それ以外は failed */
export type TestFailurePhase = "reset" | "beforeEach" | "fixture" | "scenario" | "client" | "timeout";

/** テストの isolation。fresh-server はサーバーとクライアントを起動し直してから実行する */
export type Isolation = "reset" | "fresh-server";

export interface FukurouInfo {
  version: string;
  portablemc: string;
  /** 結果を書いたランナー。JVM 版（fukurou-kotlin）は "kotlin"、Python 版は null か省略 */
  runner?: string | null;
}

export interface MinecraftInfo {
  version: string;
  server: "paper";
  build: number | null;
  channel: string | null;
}

export interface JavaInfo {
  /** サーバーを起動した Java の major 番号 */
  server: number | null;
}

export interface PluginInfo {
  file: string;
  sha256: string;
  name: string | null;
  version: string | null;
  role: "under-test" | "dependency";
  source: string | null;
  classFileMajor: number | null;
  /** サーバーログで有効化を確認できたか。確認前に失敗した場合は null */
  enabled: boolean | null;
}

/** run に参加した（させようとした）プレイヤー。op はテストごとなので tests[].players にある */
export interface PlayerInfo {
  name: string;
  joined: boolean;
}

/** テストに参加するプレイヤーと、そのテストでの op */
export interface TestPlayer {
  name: string;
  op: boolean;
}

export interface ArenaSpec {
  size: number;
  height: number;
}

/** スイートの設定の要約。テスト発見前に失敗した run では suite 自体が null */
export interface SuiteInfo {
  /** "file:<path>"。スイート無し（--scenario-file / --scenario だけ）の run では null */
  source: string | null;
  /** スイートファイルの sha256。source と同じくスイート無しなら null */
  sha256: string | null;
  isolation: Isolation;
  settle: number;
  gamemode: string;
  arena: ArenaSpec | false;
}

/** CLI / アクションで指定した選択条件の記録 */
export interface SelectionInfo {
  tests: string[];
  tags: string[];
  isolation: Isolation | null;
  failFast: boolean;
}

export interface Summary {
  total: number;
  passed: number;
  failed: number;
  error: number;
  skipped: number;
}

export interface LogInfo {
  kind: "harness" | "server" | "client" | "crash";
  path: string;
  player: string | null;
}

/** 1 つのサーバーセッション（initial と、fresh-server のテストごとの再起動） */
export interface SessionInfo {
  index: number;
  kind: "initial" | "fresh-server";
  startedAt: string;
  finishedAt: string | null;
  /** このセッションに参加させたプレイヤー */
  players: string[];
  /** このセッションで実行したテストの id */
  tests: string[];
  /** このセッションで回収したログ（サーバー、クライアント、クラッシュレポート） */
  logs: LogInfo[];
  /** サーバーが死んだときのメッセージ */
  failure: string | null;
}

export interface StepResult {
  index: number;
  phase: StepPhase;
  /** phase が fixture のときだけ fixture の名前 */
  fixture: string | null;
  /** "server" / プレイヤー名 / null（共通アクション） */
  on: string | null;
  action: string;
  label: string;
  status: StepStatus;
  durationMs: number | null;
  error: string | null;
  /** screenshot アクションのときだけ、artifact ルートからの相対パス */
  screenshot: string | null;
  /** fukurou 2.1: parallel ブロックの中のステップならその位置。2.0 の runner は書かないので undefined もあり得る */
  parallel?: ParallelInfo | null;
  /** fukurou 2.1: 囲む repeat ブロック（外側から順）。repeat の外なら null / undefined。空配列にはならない */
  repeat?: RepeatInfo[] | null;
  /** fukurou 2.1: 実行を始めた / 終えた時刻（ISO 8601）。実行しなかったステップは null */
  startedAt?: string | null;
  finishedAt?: string | null;
}

/** parallel ブロックの中の位置。同じ block のステップは同時に実行され、同じ lane のステップは順に実行された */
export interface ParallelInfo {
  /** テスト内の parallel ブロックの通し番号（0 始まり、計画順） */
  block: number;
  /** ブロックの中の子の番号（0 始まり） */
  lane: number;
}

/** repeat ブロックの何回目か */
export interface RepeatInfo {
  /** テスト内の repeat ブロックの通し番号（0 始まり、計画順） */
  block: number;
  /** 1 始まりの繰り返し番号 */
  iteration: number;
  /** 繰り返しの回数（times） */
  of: number;
}

export interface RunFailure {
  phase: RunFailurePhase;
  message: string;
}

export interface TestFailure {
  phase: TestFailurePhase;
  message: string;
  /** そのテストの steps 配列の添字（= steps[].index） */
  stepIndex: number | null;
}

/** ハーネスのリセット（ステップではない）の記録 */
export interface ResetInfo {
  durationMs: number;
  error: string | null;
}

export interface ScreenshotInfo {
  player: string;
  name: string;
  /** 例: tests/stamp-thinking-face/screenshots/Alice/after-stamp.png（artifact ルートからの相対パス） */
  path: string;
  width: number;
  height: number;
  stepIndex: number | null;
}

/** ログファイルの中でこのテストに当たる範囲。1 始まりで両端を含む行番号 */
export interface LogRange {
  from: number;
  to: number;
}

/** 1 テストの結果 */
export interface TestResult {
  id: string;
  name: string;
  /** 実行順 */
  order: number;
  /** "file:<path>" または "inline" */
  source: string;
  sha256: string;
  tags: string[];
  isolation: Isolation;
  timeout: number;
  /** テストの versions: 制約。無ければ null */
  versions: string | null;
  /** 実行したセッションの index。実行しなかったテストは null */
  session: number | null;
  status: TestStatus;
  skipReason: string | null;
  players: TestPlayer[];
  /** 実行しなかったテストは null */
  reset: ResetInfo | null;
  steps: StepResult[];
  failure: TestFailure | null;
  screenshots: ScreenshotInfo[];
  /** 回収したログファイルのパス → このテストの行範囲。実行しなかったテストは null */
  logRanges: Record<string, LogRange> | null;
  startedAt: string | null;
  durationMs: number | null;
}

export interface CiInfo {
  repository: string | null;
  sha: string | null;
  ref: string | null;
  runId: string | null;
  runAttempt: string | null;
  serverUrl: string | null;
}

/** result.json（schemaVersion 2）のルート */
export interface ResultV2 {
  schemaVersion: 2;
  /** "<server>-<minecraft version>[-<label>]"（例: paper-1.21.11、paper-26.3-stamp-arena） */
  id: string;
  /** 1 つのジョブが同じバージョンで複数のサーバーを実行したとき（fukurou-kotlin）のサーバーのラベル */
  label?: string | null;
  status: RunStatus;
  fukurou: FukurouInfo;
  minecraft: MinecraftInfo;
  java: JavaInfo;
  plugins: PluginInfo[];
  suite: SuiteInfo | null;
  selection: SelectionInfo;
  players: PlayerInfo[];
  summary: Summary;
  sessions: SessionInfo[];
  /** 実行順 */
  tests: TestResult[];
  /** インフラ失敗。無ければ null */
  failure: RunFailure | null;
  /** run 全体のログ（harness.log）。セッションごとのログは sessions[].logs */
  logs: LogInfo[];
  startedAt: string;
  finishedAt: string | null;
  durationMs: number | null;
  ci: CiInfo | null;
}

/** 別の schemaVersion を含む、manifest に埋め込まれうる result.json。中身は検査してから使う */
export type AnyResult = ResultV2 | { schemaVersion: number; [key: string]: unknown };

export interface ManifestGenerator {
  name: string;
  version: string;
}

export interface ManifestCi {
  repository: string | null;
  sha: string | null;
  runId: string | null;
  runUrl: string | null;
}

/** run（バージョン）単位の件数 */
export interface RunsSummary {
  total: number;
  passed: number;
  failed: number;
  error: number;
}

export interface ManifestSummary {
  runs: RunsSummary;
  /** テスト × バージョンの件数 */
  tests: Summary;
}

/** 全 run のテストの和集合の 1 行 */
export interface ManifestTest {
  id: string;
  name: string;
  tags: string[];
  /** このテストのプレイヤーの和集合 */
  players: string[];
  /** このテストのスクリーンショット名の和集合（failure を除く） */
  shots: string[];
  /** error > failed > passed > skipped の優先で決めた全 run を通した状態 */
  status: TestStatus;
  /** run id → その run でのテストの状態。無い run は「not run」 */
  cells: Record<string, TestStatus>;
}

export interface ManifestRun {
  id: string;
  /** 元の artifact 名（例: fukurou-paper-1.21.11）。入れ子の束の run は "<artifact>/<run id>" */
  artifact: string;
  /** サイト内のこの run のディレクトリ（例: runs/paper-1.21.11/）。result 内のパスはここからの相対 */
  base: string;
  /** result の label（ラベルの無い run と古い manifest では null か省略） */
  label?: string | null;
  status: RunStatus;
  /** result.json が無かった artifact では null */
  result: AnyResult | null;
  /**
   * result.json が無い run でも、サイトにコピーされたログ（harness.log など）。
   * result がある run では result.logs / sessions[].logs を使うので、ここは省略されうる
   */
  logs?: LogInfo[];
}

/** manifest.json / manifest.js（schemaVersion 2）のルート */
export interface ManifestV2 {
  schemaVersion: 2;
  generator: ManifestGenerator;
  generatedAt: string;
  title: string;
  ci: ManifestCi | null;
  summary: ManifestSummary;
  /** 全 run のプレイヤーの和集合 */
  players: string[];
  /** 全 run のテストの和集合（最初に現れた run の実行順） */
  tests: ManifestTest[];
  /** Minecraft のリリース順（古い順） */
  runs: ManifestRun[];
  warnings: string[];
}
