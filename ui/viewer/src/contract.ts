// docs/contract.md と src/fukurou/result/model.py（ResultV1）を TypeScript に写した型。
// ランナーとビューアの唯一のインターフェースなので、フィールド名・null 許容は契約どおりに保つ。
// 契約上「フィールド追加は後方互換」なので、ここに無いキーが来ても無視するだけでよい。

/** 実行全体の結果。passed: 全ステップ成功 / failed: シナリオ失敗 / error: インフラ失敗 */
export type RunStatus = "passed" | "failed" | "error";

/** ステップ単位の結果。失敗後に実行されなかったステップは skipped になる */
export type StepStatus = "passed" | "failed" | "skipped";

/** どの段階で失敗したか */
export type FailurePhase = "setup" | "server-start" | "client-join" | "scenario" | "teardown";

export interface FukurouInfo {
  version: string;
  portablemc: string;
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

export interface ScenarioInfo {
  name: string;
  /** "file:<path>" または "inline" */
  source: string;
  sha256: string;
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

export interface PlayerInfo {
  name: string;
  op: boolean;
  joined: boolean;
}

export interface StepResult {
  index: number;
  /** "server" / プレイヤー名 / null（共通アクション） */
  on: string | null;
  action: string;
  label: string;
  status: StepStatus;
  durationMs: number | null;
  error: string | null;
  /** screenshot アクションのときだけ、artifact ルートからの相対パス */
  screenshot: string | null;
}

export interface Failure {
  phase: FailurePhase;
  message: string;
  stepIndex: number | null;
}

export interface ScreenshotInfo {
  player: string;
  name: string;
  /** 例: screenshots/Alice/stamp.png（artifact ルートからの相対パス） */
  path: string;
  width: number;
  height: number;
  stepIndex: number | null;
}

export interface LogInfo {
  kind: "harness" | "server" | "client" | "crash";
  path: string;
  player: string | null;
}

export interface CiInfo {
  repository: string | null;
  sha: string | null;
  ref: string | null;
  runId: string | null;
  runAttempt: string | null;
  serverUrl: string | null;
}

/** result.json（schemaVersion 1）のルート */
export interface ResultV1 {
  schemaVersion: 1;
  /** "<server>-<minecraft version>"（例: paper-1.21.11） */
  id: string;
  status: RunStatus;
  fukurou: FukurouInfo;
  minecraft: MinecraftInfo;
  java: JavaInfo;
  scenario: ScenarioInfo | null;
  plugins: PluginInfo[];
  players: PlayerInfo[];
  steps: StepResult[];
  failure: Failure | null;
  screenshots: ScreenshotInfo[];
  logs: LogInfo[];
  startedAt: string;
  finishedAt: string | null;
  durationMs: number | null;
  ci: CiInfo | null;
}

/** 将来の schemaVersion を含む、manifest に埋め込まれうる result.json。中身は検査してから使う */
export type AnyResult = ResultV1 | { schemaVersion: number; [key: string]: unknown };

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

export interface ManifestSummary {
  total: number;
  passed: number;
  failed: number;
  error: number;
}

export interface ManifestRun {
  id: string;
  /** 元の artifact 名（例: fukurou-paper-1.21.11） */
  artifact: string;
  /** サイト内のこの run のディレクトリ（例: runs/paper-1.21.11/）。result 内のパスはここからの相対 */
  base: string;
  status: RunStatus;
  /** result.json が無かった artifact では null */
  result: AnyResult | null;
}

/** manifest.json / manifest.js（schemaVersion 1）のルート */
export interface ManifestV1 {
  schemaVersion: 1;
  generator: ManifestGenerator;
  generatedAt: string;
  title: string;
  ci: ManifestCi | null;
  summary: ManifestSummary;
  /** 全 run のプレイヤーの和集合 */
  players: string[];
  /** 全 run のスクリーンショット名の和集合（failure を除く） */
  shots: string[];
  /** Minecraft のリリース順（古い順） */
  runs: ManifestRun[];
  warnings: string[];
}
