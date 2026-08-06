/** Docker logs 时间参数校验与 remote args 组装（供 MCP 工具与单测复用）。 */

const MAX_TIME_ARG_LEN = 128;
/** Docker 相对时间：数字 + s/m/h/d/w/y */
const RELATIVE_TIME_RE = /^\d+[smhdwy]$/;

export function isRelativeDockerLogTime(value: string): boolean {
  return RELATIVE_TIME_RE.test(value);
}

export function normalizeDockerLogTimeArg(
  value: string | null | undefined,
  label: "since" | "until"
): { ok: true; value: string } | { ok: false; error: string } {
  if (value === null || value === undefined) {
    return { ok: false, error: `${label} 参数不能为空` };
  }
  const trimmed = value.trim();
  if (!trimmed) {
    return { ok: false, error: `${label} 参数不能为空字符串` };
  }
  if (trimmed.length > MAX_TIME_ARG_LEN) {
    return { ok: false, error: `${label} 参数过长（最多 ${MAX_TIME_ARG_LEN} 字符）` };
  }
  if (isRelativeDockerLogTime(trimmed)) {
    return { ok: true, value: trimmed };
  }
  const parsed = Date.parse(trimmed);
  if (!Number.isFinite(parsed)) {
    return {
      ok: false,
      error: `${label} 参数格式无效，请使用 RFC3339 绝对时间（如 2026-06-24T01:00:00+08:00）或相对时间（如 10m、2h）`,
    };
  }
  return { ok: true, value: trimmed };
}

export function validateDockerLogTimeWindow(
  since: string | undefined,
  until: string | undefined
): { ok: true } | { ok: false; error: string } {
  if (!since || !until) {
    return { ok: true };
  }
  if (isRelativeDockerLogTime(since) || isRelativeDockerLogTime(until)) {
    return { ok: true };
  }
  const sinceMs = Date.parse(since);
  const untilMs = Date.parse(until);
  if (Number.isFinite(sinceMs) && Number.isFinite(untilMs) && sinceMs > untilMs) {
    return { ok: false, error: "since 不能晚于 until" };
  }
  return { ok: true };
}

function clampInt(
  value: number | null | undefined,
  defaultVal: number,
  min: number,
  max: number
): number {
  if (value === null || value === undefined) return defaultVal;
  const n = Number(value);
  if (!Number.isFinite(n)) return defaultVal;
  return Math.min(max, Math.max(min, Math.floor(n)));
}

export type DockerLogsArgsInput = {
  dockerPrefix: string[];
  container: string;
  tail?: number | null;
  timestamps?: boolean | null;
  since?: string | null;
  until?: string | null;
  /** 为 true 时在 args 中加入 -f（remote_docker_follow） */
  follow?: boolean;
};

export type BuildDockerLogsRemoteArgsResult =
  | {
      ok: true;
      args: string[];
      /** 实际传给 docker 的 tail 值（数字或 "all"） */
      tailUsed: string;
      since?: string;
      until?: string;
    }
  | { ok: false; error: string };

/**
 * 组装远程 docker logs 命令参数。
 * 若指定 since/until 且未显式传 tail，则使用 --tail=all 以返回完整时间窗口。
 */
export function buildDockerLogsRemoteArgs(input: DockerLogsArgsInput): BuildDockerLogsRemoteArgsResult {
  const container = input.container.trim();
  if (!container) {
    return { ok: false, error: "container 参数不能为空" };
  }

  let sinceNorm: string | undefined;
  let untilNorm: string | undefined;

  if (input.since !== null && input.since !== undefined) {
    const sinceResult = normalizeDockerLogTimeArg(input.since, "since");
    if (!sinceResult.ok) return sinceResult;
    sinceNorm = sinceResult.value;
  }
  if (input.until !== null && input.until !== undefined) {
    const untilResult = normalizeDockerLogTimeArg(input.until, "until");
    if (!untilResult.ok) return untilResult;
    untilNorm = untilResult.value;
  }

  const windowCheck = validateDockerLogTimeWindow(sinceNorm, untilNorm);
  if (!windowCheck.ok) return windowCheck;

  const hasTimeFilter = Boolean(sinceNorm || untilNorm);
  const tailExplicit = input.tail !== null && input.tail !== undefined;

  let tailUsed: string;
  if (hasTimeFilter && !tailExplicit) {
    tailUsed = "all";
  } else {
    tailUsed = String(clampInt(input.tail ?? null, 200, 1, 10000));
  }

  const ts = input.timestamps ?? true;
  const args: string[] = [...input.dockerPrefix, "logs"];
  if (input.follow) {
    args.push("-f");
  }
  args.push(`--tail=${tailUsed}`);
  if (sinceNorm) {
    args.push(`--since=${sinceNorm}`);
  }
  if (untilNorm) {
    args.push(`--until=${untilNorm}`);
  }
  if (ts) {
    args.push("--timestamps");
  }
  args.push(container);

  return {
    ok: true,
    args,
    tailUsed,
    ...(sinceNorm ? { since: sinceNorm } : {}),
    ...(untilNorm ? { until: untilNorm } : {}),
  };
}
