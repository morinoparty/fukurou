import type { RepeatInfo, StepResult } from "../contract";

// fukurou 2.1 の parallel / repeat ブロックは計画時に展開され、result.json には平らなステップの列しか残らない。
// 各ステップの parallel（block, lane）と repeat（外側から順の block, iteration, of）から、表示用の入れ子を組み直す。
// 契約上、同じ parallel.block のステップは連続し（lane 0 から順）、同じ repeat の回も連続する。

/** 1 ステップ */
export interface StepLeaf {
  kind: "step";
  step: StepResult;
}

/** repeat ブロックの 1 回分 */
export interface RepeatIteration {
  kind: "iteration";
  /** 表示と開閉状態の鍵。repeat の block と iteration から作る */
  key: string;
  repeat: RepeatInfo;
  children: StepNode[];
}

/** parallel ブロック。lanes は lane 番号順 */
export interface ParallelBlock {
  kind: "parallel";
  key: string;
  block: number;
  lanes: ParallelLane[];
}

/** parallel ブロックの 1 レーン。repeat の子ならステップが複数ある */
export interface ParallelLane {
  lane: number;
  children: StepNode[];
}

export type StepNode = StepLeaf | RepeatIteration | ParallelBlock;

/** 同じ repeat の同じ回か */
function sameIteration(a: RepeatInfo | undefined, b: RepeatInfo | undefined): boolean {
  return a !== undefined && b !== undefined && a.block === b.block && a.iteration === b.iteration;
}

/** start から、条件を満たすステップが続く数 */
function runLength(steps: StepResult[], start: number, matches: (step: StepResult) => boolean): number {
  let end = start;
  while (end < steps.length && matches(steps[end] as StepResult)) end += 1;
  return end - start;
}

/**
 * 平らなステップの列を parallel / repeat の入れ子に組み直す。
 * depth はすでに外側で開いた repeat の段数、inParallel は外側で開いた parallel ブロックの番号。
 *
 * 1 つのステップに parallel と repeat[depth] の両方があるとき、どちらが外側かはフィールドからは分からない。
 * 連続する範囲の広いほうが外側になる（repeat の中の parallel なら、その回の範囲がブロックを含む。
 * parallel のレーンが repeat なら、1 回分の範囲はブロックより狭い）。同じ広さなら repeat を外側とする
 */
export function buildStepTree(steps: StepResult[], depth = 0, inParallel: number | null = null): StepNode[] {
  const nodes: StepNode[] = [];
  let index = 0;
  while (index < steps.length) {
    const step = steps[index] as StepResult;
    const repeat = step.repeat?.[depth];
    const parallel = step.parallel && step.parallel.block !== inParallel ? step.parallel : null;
    const repeatSpan = repeat ? runLength(steps, index, (candidate) => sameIteration(candidate.repeat?.[depth], repeat)) : 0;
    const parallelSpan = parallel ? runLength(steps, index, (candidate) => candidate.parallel?.block === parallel.block) : 0;

    if (repeat && repeatSpan >= parallelSpan) {
      const span = steps.slice(index, index + repeatSpan);
      nodes.push({
        kind: "iteration",
        key: `r${repeat.block}.${repeat.iteration}`,
        repeat,
        children: buildStepTree(span, depth + 1, inParallel),
      });
      index += repeatSpan;
    } else if (parallel) {
      const span = steps.slice(index, index + parallelSpan);
      // レーン番号ごとに分ける。契約上は lane 0 から順に並ぶが、念のため番号順に並べ直す
      const byLane = new Map<number, StepResult[]>();
      for (const candidate of span) {
        const lane = candidate.parallel?.lane ?? 0;
        byLane.set(lane, [...(byLane.get(lane) ?? []), candidate]);
      }
      const lanes = [...byLane.entries()]
        .sort(([a], [b]) => a - b)
        .map(([lane, laneSteps]) => ({ lane, children: buildStepTree(laneSteps, depth, parallel.block) }));
      nodes.push({ kind: "parallel", key: `p${parallel.block}`, block: parallel.block, lanes });
      index += parallelSpan;
    } else {
      nodes.push({ kind: "step", step });
      index += 1;
    }
  }
  return nodes;
}

/** 入れ子の中のステップを計画順に集める */
export function leafSteps(nodes: StepNode[]): StepResult[] {
  return nodes.flatMap((node) => {
    if (node.kind === "step") return [node.step];
    if (node.kind === "iteration") return leafSteps(node.children);
    return node.lanes.flatMap((lane) => leafSteps(lane.children));
  });
}

/** ISO 8601 の時刻をミリ秒に。無い・解釈できないときは null */
export function parseTime(iso: string | null | undefined): number | null {
  if (!iso) return null;
  const time = Date.parse(iso);
  return Number.isNaN(time) ? null : time;
}

/** ステップの列が実際に掛かった時間の範囲（startedAt の最小から finishedAt の最大）。時刻が無ければ null */
export function timeSpan(steps: StepResult[]): { start: number; end: number } | null {
  let start = Number.POSITIVE_INFINITY;
  let end = Number.NEGATIVE_INFINITY;
  for (const step of steps) {
    const started = parseTime(step.startedAt);
    const finished = parseTime(step.finishedAt);
    if (started === null || finished === null) continue;
    start = Math.min(start, started);
    end = Math.max(end, finished);
  }
  return Number.isFinite(start) && Number.isFinite(end) && end >= start ? { start, end } : null;
}

/**
 * 入れ子の所要時間（壁時計）。parallel ブロックは時刻があればその範囲、無ければ最も長いレーン。
 * 単純に durationMs を足すと同時に動いたステップを二重に数えてしまうため
 */
export function wallDuration(nodes: StepNode[]): number {
  return nodes.reduce((sum, node) => {
    if (node.kind === "step") return sum + (node.step.durationMs ?? 0);
    if (node.kind === "iteration") return sum + wallDuration(node.children);
    const span = timeSpan(leafSteps([node]));
    if (span) return sum + (span.end - span.start);
    return sum + Math.max(0, ...node.lanes.map((lane) => wallDuration(lane.children)));
  }, 0);
}

/** 1 本の時間バー。left / width はブロックの範囲に対する割合（0〜100） */
export interface TimeBar {
  step: StepResult;
  left: number;
  width: number;
}

/**
 * parallel ブロックのレーンごとの時間バー。startedAt / finishedAt の無いステップ（実行されなかった、2.0 の runner）は描かない。
 * ブロック全体で時刻が無ければ null
 */
export function laneBars(
  block: ParallelBlock,
): { span: { start: number; end: number }; lanes: { lane: ParallelLane; bars: TimeBar[] }[] } | null {
  const span = timeSpan(leafSteps([block]));
  if (!span) return null;
  // 0 ms のブロックでも割り算が壊れないように 1 ms とみなす
  const total = Math.max(1, span.end - span.start);
  const lanes = block.lanes.map((lane) => {
    const bars: TimeBar[] = [];
    for (const step of leafSteps(lane.children)) {
      const started = parseTime(step.startedAt);
      const finished = parseTime(step.finishedAt);
      if (started === null || finished === null) continue;
      bars.push({
        step,
        left: ((started - span.start) / total) * 100,
        width: (Math.max(0, finished - started) / total) * 100,
      });
    }
    return { lane, bars };
  });
  return { span, lanes };
}

/** スクリーンショットを撮ったステップの repeat から "iteration 2/3" のような説明を作る。repeat の外なら null */
export function iterationLabel(repeat: RepeatInfo[] | null | undefined): string | null {
  if (!repeat || repeat.length === 0) return null;
  return repeat.map((entry) => `iteration ${entry.iteration}/${entry.of}`).join(" · ");
}
