/**
 * alphafrog-debug-mcp — stdio MCP，与历史 Python server.py 行为对齐。
 * 禁止向 stdout 写入日志（会破坏 JSON-RPC），仅使用 stderr。
 */
import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { buildDockerLogsRemoteArgs } from "./dockerLogs.js";
import {
  buildLogBody,
  buildLogFileName,
  formatSaveFileContent,
  generateLogNonce,
  resolveLogSaveDir,
  SAVE_MAX_BYTES,
  saveLogToFile,
  truncateResponseFields,
  truncateTextByBytes,
} from "./logCapture.js";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import dotenv from "dotenv";
import pg from "pg";
import { parse as parseShell, quote } from "shell-quote";
import { z } from "zod";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// ---------- 环境加载（与 Python 一致：默认仓库根 .env）----------
function loadEnv(): void {
  const custom = process.env.ALPHAFROG_DEBUG_DOTENV_PATH?.trim();
  const repoRoot = path.resolve(__dirname, "..", "..");
  const dotenvPath = custom && existsSync(custom) ? custom : path.join(repoRoot, ".env");
  if (existsSync(dotenvPath)) {
    dotenv.config({ path: dotenvPath, quiet: true });
  }
}

loadEnv();

const HOST_RE = /^[A-Za-z0-9._-]+$/;

function envList(key: string): string[] {
  const raw = (process.env[key] ?? "").trim();
  if (!raw) return [];
  return raw.split(",").map((s) => s.trim()).filter(Boolean);
}

function resolveEnvToHost(env: string): { host: string } | { error: string } {
  if (env !== "test" && env !== "prod") {
    return { error: "env 必须为 test 或 prod" };
  }
  const key = `ALPHAFROG_DEBUG_SSH_HOST_${env.toUpperCase()}`;
  const resolved = (process.env[key] ?? "").trim();
  if (!resolved) {
    return {
      error:
        env === "test"
          ? "测试环境远程访问尚未在服务端配置完成"
          : "生产环境远程访问尚未在服务端配置完成",
    };
  }
  if (!HOST_RE.test(resolved)) {
    return { error: "服务端远程主机配置格式无效" };
  }
  const allowed = envList("ALPHAFROG_DEBUG_SSH_HOSTS");
  if (allowed.length > 0 && !allowed.includes(resolved)) {
    return { error: "远程主机不在服务端允许列表中" };
  }
  return { host: resolved };
}

function repoPathForEnv(
  env: string,
  repoPathOverride: string | null | undefined
): { path: string } | { error: string } {
  if (repoPathOverride?.trim()) {
    return { path: repoPathOverride.trim() };
  }
  let p =
    env === "test"
      ? (process.env.ALPHAFROG_DEBUG_REPO_PATH_TEST ?? "").trim()
      : (process.env.ALPHAFROG_DEBUG_REPO_PATH_PROD ?? "").trim();
  if (!p) {
    p = (process.env.ALPHAFROG_DEBUG_DEFAULT_REPO_PATH ?? "").trim();
  }
  if (!p) {
    return { error: "远程仓库路径尚未在服务端配置完成" };
  }
  return { path: p };
}

function parseShellArgs(raw: string): string[] {
  const tokens = parseShell(raw) as Array<string | { op: string }>;
  return tokens.flatMap((t) => (typeof t === "string" ? [t] : []));
}

function sshBaseArgs(host: string): string[] {
  const args: string[] = ["ssh"];
  const sshConfig = process.env.ALPHAFROG_DEBUG_SSH_CONFIG?.trim();
  if (sshConfig) {
    args.push("-F", sshConfig);
  }
  const extra = process.env.ALPHAFROG_DEBUG_SSH_ARGS?.trim();
  if (extra) {
    args.push(...parseShellArgs(extra));
  }
  args.push(host);
  return args;
}

function dockerCmd(): string[] {
  return parseShellArgs(process.env.ALPHAFROG_DEBUG_DOCKER_CMD ?? "docker");
}

function gitCmd(): string[] {
  return parseShellArgs(process.env.ALPHAFROG_DEBUG_GIT_CMD ?? "git");
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

function truncateBytes(buf: Buffer, maxBytes: number | null | undefined): [Buffer, boolean] {
  if (maxBytes === null || maxBytes === undefined || maxBytes <= 0) {
    return [buf, false];
  }
  if (buf.length <= maxBytes) return [buf, false];
  return [buf.subarray(0, maxBytes), true];
}

function filterOutput(text: string, grep: string | null | undefined): string {
  if (!grep) return text;
  const lines = text.split(/\r?\n/);
  if (grep.startsWith("re:")) {
    const pattern = new RegExp(grep.slice(3));
    return lines.filter((line) => pattern.test(line)).join("\n");
  }
  return lines.filter((line) => line.includes(grep)).join("\n");
}

type SshRunResult = {
  ok: boolean;
  exit_code: number;
  timed_out: boolean;
  duration_ms: number;
  command: string[];
  stdout: string;
  stderr: string;
  stdout_truncated: boolean;
  stderr_truncated: boolean;
};

function redactSshToolResult(result: SshRunResult, host: string): Omit<SshRunResult, "command"> {
  const { command: _c, ...rest } = result;
  const out: Omit<SshRunResult, "command"> = { ...rest };
  if (host && result.stderr) {
    out.stderr = result.stderr.split(host).join("[远程主机已隐藏]");
  }
  if (host && result.stdout) {
    out.stdout = result.stdout.split(host).join("[远程主机已隐藏]");
  }
  return out;
}

async function runSsh(
  host: string,
  remoteArgs: string[],
  options: { timeoutSeconds?: number | null; maxBytes?: number | null } = {}
): Promise<SshRunResult> {
  const remoteJoined = quote(remoteArgs);
  const cmd = [...sshBaseArgs(host), remoteJoined];
  const start = performance.now();
  const child = spawn(cmd[0], cmd.slice(1), {
    stdio: ["ignore", "pipe", "pipe"],
  });

  const timeoutMs =
    options.timeoutSeconds !== null && options.timeoutSeconds !== undefined && options.timeoutSeconds > 0
      ? options.timeoutSeconds * 1000
      : undefined;

  let timedOut = false;
  let timeoutId: ReturnType<typeof setTimeout> | undefined;

  const stdoutChunks: Buffer[] = [];
  const stderrChunks: Buffer[] = [];

  const stdoutPromise = new Promise<void>((resolve) => {
    child.stdout?.on("data", (d: Buffer) => stdoutChunks.push(d));
    child.stdout?.on("end", () => resolve());
  });
  const stderrPromise = new Promise<void>((resolve) => {
    child.stderr?.on("data", (d: Buffer) => stderrChunks.push(d));
    child.stderr?.on("end", () => resolve());
  });

  if (timeoutMs !== undefined) {
    timeoutId = setTimeout(() => {
      timedOut = true;
      child.kill("SIGKILL");
    }, timeoutMs);
  }

  const exitCode = await new Promise<number>((resolve) => {
    child.on("exit", (code) => resolve(code ?? 1));
    child.on("error", () => resolve(1));
  });

  if (timeoutId) clearTimeout(timeoutId);
  await Promise.all([stdoutPromise, stderrPromise]);

  let stdoutBuf = Buffer.concat(stdoutChunks);
  let stderrBuf = Buffer.concat(stderrChunks);
  let stdoutTruncated = false;
  let stderrTruncated = false;
  let maxB = options.maxBytes;
  if (maxB !== null && maxB !== undefined && maxB > 0) {
    const [o, ot] = truncateBytes(Buffer.from(stdoutBuf), maxB);
    const [e, et] = truncateBytes(Buffer.from(stderrBuf), maxB);
    stdoutBuf = Buffer.from(o);
    stderrBuf = Buffer.from(e);
    stdoutTruncated = ot;
    stderrTruncated = et;
  }

  const duration_ms = Math.round(performance.now() - start);

  return {
    ok: exitCode === 0 && !timedOut,
    exit_code: exitCode,
    timed_out: timedOut,
    duration_ms,
    command: cmd,
    stdout: stdoutBuf.toString("utf8"),
    stderr: stderrBuf.toString("utf8"),
    stdout_truncated: stdoutTruncated,
    stderr_truncated: stderrTruncated,
  };
}

// ---------- SQL 校验（与 Python 一致）----------
const ALLOWED_TABLE_PREFIX = "alphafrog_";
const DANGEROUS_KEYWORDS =
  /\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|TRUNCATE|GRANT|REVOKE|EXEC|EXECUTE|COPY|VACUUM|MERGE)\b/i;
const TABLE_REF_RE = /\b(?:FROM|JOIN)\s+([a-zA-Z_][a-zA-Z0-9_]*)/gi;
const MAX_ROWS = 100;

/** 解析最外层位于语句末尾的 LIMIT（忽略子查询内的 LIMIT）。 */
function parseOuterLimit(sql: string): { limit: number; start: number; end: number } | null {
  const trimmed = sql.trim();

  let m = trimmed.match(/\bOFFSET\s+(\d+)\s+LIMIT\s+(\d+)\s*$/i);
  if (m && m.index !== undefined) {
    return { limit: parseInt(m[2], 10), start: m.index, end: trimmed.length };
  }

  m = trimmed.match(/\bLIMIT\s+(\d+)\s+OFFSET\s+(\d+)\s*$/i);
  if (m && m.index !== undefined) {
    return { limit: parseInt(m[1], 10), start: m.index, end: trimmed.length };
  }

  m = trimmed.match(/\bLIMIT\s+(\d+)\s*$/i);
  if (m && m.index !== undefined) {
    return { limit: parseInt(m[1], 10), start: m.index, end: trimmed.length };
  }

  return null;
}

/** 保留 <= MAX_ROWS 的外层 LIMIT；超过则截断为 MAX_ROWS；未写则追加 LIMIT MAX_ROWS。 */
function applyRowLimit(sql: string): { sql: string; effectiveLimit: number } {
  const safeSql = sql.trim().replace(/;+\s*$/, "").trim();
  const outer = parseOuterLimit(safeSql);

  if (!outer) {
    return { sql: `${safeSql} LIMIT ${MAX_ROWS}`, effectiveLimit: MAX_ROWS };
  }

  const effectiveLimit = Math.min(outer.limit, MAX_ROWS);
  if (outer.limit <= MAX_ROWS) {
    return { sql: safeSql, effectiveLimit };
  }

  const before = safeSql.slice(0, outer.start);
  const clause = safeSql.slice(outer.start, outer.end);
  const cappedClause = clause.replace(/\bLIMIT\s+\d+/i, `LIMIT ${effectiveLimit}`);
  return { sql: (before + cappedClause).trim(), effectiveLimit };
}

function validateSql(sql: string): string | null {
  const stripped = sql.trim();
  if (!stripped.toUpperCase().startsWith("SELECT")) {
    return "Only SELECT statements are allowed";
  }
  if (DANGEROUS_KEYWORDS.test(stripped)) {
    return "Dangerous keyword detected";
  }
  const tables = [...stripped.matchAll(TABLE_REF_RE)].map((m) => m[1]);
  for (const t of tables) {
    if (!t.toLowerCase().startsWith(ALLOWED_TABLE_PREFIX)) {
      return `Table '${t}' is not allowed (must start with 'alphafrog_')`;
    }
  }
  return null;
}

function pgConfigErrorMessage(env: string): string {
  return env === "test"
    ? "所选测试环境尚未在服务端完成数据库连接配置"
    : "所选生产环境尚未在服务端完成数据库连接配置";
}

function toolJson(data: Record<string, unknown>) {
  return {
    content: [{ type: "text" as const, text: JSON.stringify(data) }],
    structuredContent: data,
  };
}

/** 远程 docker 日志工具：可选落盘 + 响应字符截断。 */
async function finalizeDockerLogResult(
  toolName: string,
  inputArgs: Record<string, unknown>,
  result: SshRunResult,
  host: string,
  options: { saveToFile: boolean }
): Promise<Record<string, unknown>> {
  const invokedAt = new Date();
  const redacted = redactSshToolResult(result, host);
  const logBodyFull = buildLogBody(redacted.stdout, redacted.stderr);

  let log_saved = false;
  let log_saved_path: string | undefined;
  let log_saved_file: string | undefined;
  let log_save_truncated: boolean | undefined;

  if (options.saveToFile) {
    const dirResult = resolveLogSaveDir();
    if ("error" in dirResult) {
      return { ok: false, error: dirResult.error };
    }
    const nonce = generateLogNonce();
    const fileName = buildLogFileName(invokedAt, toolName, inputArgs, nonce);
    const { text: saveText, truncated: saveTruncated } = truncateTextByBytes(logBodyFull, SAVE_MAX_BYTES);
    log_save_truncated = saveTruncated;
    const fileContent = formatSaveFileContent(invokedAt, toolName, inputArgs, saveText);
    const saveResult = saveLogToFile(dirResult.dir, fileName, fileContent);
    if (!saveResult.ok) {
      return { ok: false, error: saveResult.error };
    }
    log_saved = true;
    log_saved_path = saveResult.filePath;
    log_saved_file = saveResult.fileName;
  }

  const truncated = truncateResponseFields(redacted.stdout, redacted.stderr);

  return {
    ...redacted,
    stdout: truncated.stdout,
    stderr: truncated.stderr,
    log_saved,
    ...(log_saved_path ? { log_saved_path, log_saved_file } : {}),
    ...(log_save_truncated ? { log_save_truncated: true } : {}),
    ...(truncated.response_truncated
      ? {
          response_truncated: true,
          response_truncation_hint: truncated.response_truncation_hint,
        }
      : {}),
  };
}

// ---------- Agent data 目录只读查询 ----------
const DATA_OPERATIONS = [
  "list",
  "tree",
  "find_name",
  "find_content",
  "stat",
  "du",
  "head",
  "tail",
  "read_range",
] as const;

type DataOperation = (typeof DATA_OPERATIONS)[number];

const READ_CONTENT_OPERATIONS: ReadonlySet<DataOperation> = new Set([
  "head",
  "tail",
  "read_range",
  "find_content",
]);

/** 远程 agent data 根目录（test/prod 分环境配置，均为可选；调用时按 env 校验）。 */
function dataRootForEnv(env: string): { path: string } | { error: string } {
  const envVar =
    env === "test" ? "ALPHAFROG_DEBUG_DATA_ROOT_TEST" : "ALPHAFROG_DEBUG_DATA_ROOT_PROD";
  const p = (process.env[envVar] ?? "").trim();
  if (!p) {
    return {
      error: `没配置 ${envVar} 环境变量，目前该工具不可用，请咨询人类用户获取信息`,
    };
  }
  return { path: p };
}

/** 校验相对路径，拒绝绝对路径、.. 与 shell 元字符。 */
function validateRelativePath(rel: string | null | undefined): { path: string } | { error: string } {
  const trimmed = (rel ?? ".").trim() || ".";
  if (trimmed.includes("\0")) {
    return { error: "路径包含非法字符" };
  }
  if (path.isAbsolute(trimmed) || trimmed.startsWith("~")) {
    return { error: "仅允许相对 data root 的路径" };
  }
  const segments = trimmed.split(/[/\\]+/).filter(Boolean);
  for (const seg of segments) {
    if (seg === "..") {
      return { error: "路径不允许包含 .." };
    }
    if (/[$`;&|<>(){}!\n\r\t]/.test(seg)) {
      return { error: "路径包含非法字符" };
    }
  }
  return { path: segments.length === 0 ? "." : segments.join("/") };
}

/** bash 单引号转义。 */
function bashSingleQuote(value: string): string {
  return `'${value.replace(/'/g, `'\\''`)}'`;
}

/** 是否命中敏感文件名（禁止读内容 / 内容搜索）。 */
function isSensitiveRelativePath(relativePath: string): boolean {
  const lower = relativePath.toLowerCase();
  const basename = relativePath.split("/").pop() ?? relativePath;
  if (basename === ".env") return true;
  if (/secret|credential/.test(lower)) return true;
  if (/\.(pem|key)$/i.test(basename)) return true;
  return false;
}

/** agent-configs 下默认禁止读内容与内容搜索。 */
function isAgentConfigsRelativePath(relativePath: string): boolean {
  const normalized = relativePath.replace(/^\.(\/|$)/, "");
  return normalized === "agent-configs" || normalized.startsWith("agent-configs/");
}

/** 读内容类操作的路径准入校验。 */
function validateReadContentPath(relativePath: string): string | null {
  if (isAgentConfigsRelativePath(relativePath)) {
    return "agent-configs 目录下不允许读取文件内容，请使用 list/stat/tree";
  }
  if (isSensitiveRelativePath(relativePath)) {
    return "该路径命中敏感文件规则，不允许读取内容";
  }
  return null;
}

function validateFindNamePattern(pattern: string | null | undefined): string | null {
  if (!pattern?.trim()) {
    return "find_name 操作需要 pattern 参数";
  }
  const p = pattern.trim();
  if (p.length > 200) {
    return "pattern 过长";
  }
  if (/[\0\n\r\t$`;&|<>(){}!]/.test(p)) {
    return "pattern 包含非法字符";
  }
  return null;
}

function validateContentQuery(query: string | null | undefined): string | null {
  if (!query?.trim()) {
    return "find_content 操作需要 query 参数";
  }
  if (query.length > 500) {
    return "query 过长";
  }
  if (query.includes("\0")) {
    return "query 包含非法字符";
  }
  return null;
}

/** 构造远程 bash 脚本：解析路径并校验仍在 data root 下，再执行 body（可使用 $TARGET）。 */
function buildDataQueryScript(dataRoot: string, relativePath: string, body: string): string {
  const rootLiteral = bashSingleQuote(dataRoot);
  const relLiteral = bashSingleQuote(relativePath);
  return [
    "set -euo pipefail",
    `ROOT=${rootLiteral}`,
    `REL=${relLiteral}`,
    'TARGET="$(realpath -m "${ROOT}/${REL}")"',
    'case "${TARGET}" in',
    '  "${ROOT}"|"${ROOT}"/*) ;;',
    "  *) echo 'ERROR:PATH_ESCAPE' >&2; exit 2 ;;",
    "esac",
    body,
  ].join("\n");
}

type DataQueryCommandOptions = {
  operation: DataOperation;
  dataRoot: string;
  relativePath: string;
  maxDepth: number;
  limit: number;
  pattern?: string | null;
  query?: string | null;
  headLines?: number;
  tailLines?: number;
  startLine?: number;
  lineCount?: number;
  maxFileBytes?: number;
  readMaxBytes?: number;
};

/** 根据 operation 构造远程只读 bash 脚本。 */
function buildDataQueryRemoteScript(opts: DataQueryCommandOptions): string {
  const { operation, dataRoot, relativePath, maxDepth, limit } = opts;

  switch (operation) {
    case "list":
      return buildDataQueryScript(
        dataRoot,
        relativePath,
        [
          'if [ ! -d "$TARGET" ]; then echo "ERROR:NOT_DIR" >&2; exit 1; fi',
          'ls -la -- "$TARGET"',
        ].join("\n")
      );
    case "tree":
      return buildDataQueryScript(
        dataRoot,
        relativePath,
        `find -P "$TARGET" -maxdepth ${maxDepth} -print 2>/dev/null | head -n ${limit}`
      );
    case "find_name": {
      const patternLit = bashSingleQuote(opts.pattern!.trim());
      return buildDataQueryScript(
        dataRoot,
        relativePath,
        `find -P "$TARGET" -maxdepth ${maxDepth} -name ${patternLit} -print 2>/dev/null | head -n ${limit}`
      );
    }
    case "stat":
      return buildDataQueryScript(dataRoot, relativePath, 'stat "$TARGET" 2>/dev/null || ls -ld "$TARGET"');
    case "du":
      return buildDataQueryScript(
        dataRoot,
        relativePath,
        `du -h --max-depth=1 "$TARGET" 2>/dev/null | head -n ${limit}`
      );
    case "head": {
      const lines = opts.headLines ?? 80;
      const maxB = opts.readMaxBytes ?? 20000;
      return buildDataQueryScript(
        dataRoot,
        relativePath,
        [
          'if [ ! -f "$TARGET" ]; then echo "ERROR:NOT_FILE" >&2; exit 1; fi',
          `head -n ${lines} -c ${maxB} "$TARGET"`,
        ].join("\n")
      );
    }
    case "tail": {
      const lines = opts.tailLines ?? 80;
      const maxB = opts.readMaxBytes ?? 20000;
      return buildDataQueryScript(
        dataRoot,
        relativePath,
        [
          'if [ ! -f "$TARGET" ]; then echo "ERROR:NOT_FILE" >&2; exit 1; fi',
          `tail -n ${lines} "$TARGET" | head -c ${maxB}`,
        ].join("\n")
      );
    }
    case "read_range": {
      const start = opts.startLine ?? 1;
      const count = opts.lineCount ?? 100;
      const end = start + count - 1;
      const maxB = opts.readMaxBytes ?? 20000;
      return buildDataQueryScript(
        dataRoot,
        relativePath,
        [
          'if [ ! -f "$TARGET" ]; then echo "ERROR:NOT_FILE" >&2; exit 1; fi',
          `sed -n '${start},${end}p' "$TARGET" | head -c ${maxB}`,
        ].join("\n")
      );
    }
    case "find_content": {
      const maxFileBytes = opts.maxFileBytes ?? 1048576;
      const fileLimit = Math.min(limit, 2000);
      const perFileMatch = 5;
      const queryLit = bashSingleQuote(opts.query!.trim());
      const body = [
        `find -P "$TARGET" -maxdepth ${maxDepth} -type f -size -${maxFileBytes}c \\`,
        "  ! -name '.env' ! -name '*.pem' ! -name '*.key' \\",
        "  ! -path '*/agent-configs/*' ! -path '*/agent-configs' \\",
        "  ! -iname '*secret*' ! -iname '*credential*' \\",
        "  -print 2>/dev/null | head -n " + fileLimit + " | \\",
        `  xargs -r grep -n -I -F -m ${perFileMatch} -- ${queryLit} 2>/dev/null | head -n ${limit} || true`,
      ].join("\n");
      return buildDataQueryScript(dataRoot, relativePath, body);
    }
    default:
      throw new Error(`unsupported operation: ${operation}`);
  }
}

function defaultMaxDepthForOperation(operation: DataOperation): number {
  return operation === "find_content" ? 4 : 2;
}

function mapRemoteDataQueryError(stderr: string, stdout: string): string | null {
  const combined = `${stderr}\n${stdout}`;
  if (combined.includes("ERROR:PATH_ESCAPE")) {
    return "目标路径超出允许的 data 根目录范围";
  }
  if (combined.includes("ERROR:NOT_DIR")) {
    return "目标不是目录";
  }
  if (combined.includes("ERROR:NOT_FILE")) {
    return "目标不是文件";
  }
  return null;
}

// ---------- MCP 注册 ----------
const server = new McpServer({
  name: "alphafrog-debug-mcp",
  version: "1.0.0",
});

const envSchema = z.enum(["test", "prod"]);

server.registerTool(
  "remote_docker_ps",
  {
    description: `List running docker containers on the remote host (compact output).
env: Target environment. Must be "test" or "prod".`,
    inputSchema: {
      env: envSchema,
    },
  },
  async ({ env }) => {
    const resolved = resolveEnvToHost(env);
    if ("error" in resolved) {
      return toolJson({ ok: false, error: resolved.error });
    }
    const formatArg = "{{.Names}}\\t{{.Image}}\\t{{.Status}}\\t{{.Ports}}";
    const result = await runSsh(resolved.host, [...dockerCmd(), "ps", "--format", formatArg]);

    const items: Array<{ name: string; image: string; status: string; ports: string }> = [];
    if (result.stdout) {
      for (const line of result.stdout.split(/\r?\n/)) {
        if (!line.trim()) continue;
        const parts = line.split("\t");
        if (parts.length >= 4) {
          items.push({
            name: parts[0].trim(),
            image: parts[1].trim(),
            status: parts[2].trim(),
            ports: parts[3].trim(),
          });
        } else if (parts.length >= 1) {
          items.push({ name: parts[0].trim(), image: "", status: "", ports: "" });
        }
      }
    }

    return toolJson({
      ok: result.ok,
      exit_code: result.exit_code,
      duration_ms: result.duration_ms,
      items,
      count: items.length,
    });
  }
);

server.registerTool(
  "remote_git_log",
  {
    description: `Show recent git log on the remote host.`,
    inputSchema: {
      env: envSchema,
      repo_path: z.string().optional().nullable(),
      limit: z.number().int().optional().nullable(),
    },
  },
  async ({ env, repo_path, limit }) => {
    const resolved = resolveEnvToHost(env);
    if ("error" in resolved) {
      return toolJson({ ok: false, error: resolved.error });
    }
    const rp = repoPathForEnv(env, repo_path);
    if ("error" in rp) {
      return toolJson({ ok: false, error: rp.error });
    }
    const limitVal = clampInt(limit, 10, 1, 200);
    const remoteArgs = [
      ...gitCmd(),
      "-C",
      rp.path,
      "log",
      `-n${limitVal}`,
      "--oneline",
      "--decorate",
    ];
    const raw = await runSsh(resolved.host, remoteArgs);
    return toolJson(redactSshToolResult(raw, resolved.host) as unknown as Record<string, unknown>);
  }
);

server.registerTool(
  "remote_docker_logs",
  {
    description: `Fetch docker logs on the remote host (non-follow). Optional since/until filter logs by time window (RFC3339 or relative like 10m). When since or until is set and tail is omitted, returns the full window (--tail=all). Response stdout/stderr are capped at 5000 chars; set save_to_file=true (requires ALPHAFROG_DEBUG_LOG_SAVE_DIR) to persist up to 1MB to disk.`,
    inputSchema: {
      env: envSchema,
      container: z.string().default(""),
      tail: z.number().int().optional().nullable(),
      grep: z.string().optional().nullable(),
      timestamps: z.boolean().optional().nullable(),
      since: z.string().optional().nullable(),
      until: z.string().optional().nullable(),
      max_bytes: z.number().int().optional().nullable(),
      timeout_seconds: z.number().int().optional().nullable(),
      save_to_file: z.boolean().optional().nullable(),
    },
  },
  async ({ env, container, tail, grep, timestamps, since, until, max_bytes, timeout_seconds, save_to_file }) => {
    const argsResult = buildDockerLogsRemoteArgs({
      dockerPrefix: dockerCmd(),
      container: container ?? "",
      tail,
      timestamps,
      since,
      until,
    });
    if (!argsResult.ok) {
      return toolJson({ ok: false, error: argsResult.error });
    }
    const resolved = resolveEnvToHost(env);
    if ("error" in resolved) {
      return toolJson({ ok: false, error: resolved.error });
    }
    const saveToFile = save_to_file === true;
    const fetchMaxBytes = saveToFile ? SAVE_MAX_BYTES : (max_bytes ?? 20000);
    let result = await runSsh(resolved.host, argsResult.args, {
      timeoutSeconds: timeout_seconds ?? 30,
      maxBytes: fetchMaxBytes,
    });
    if (grep) {
      result = { ...result, stdout: filterOutput(result.stdout, grep) };
    }
    const inputArgs = {
      env,
      container,
      tail,
      grep,
      timestamps,
      since,
      until,
      max_bytes,
      timeout_seconds,
      save_to_file,
    };
    const payload = await finalizeDockerLogResult("remote_docker_logs", inputArgs, result, resolved.host, {
      saveToFile,
    });
    return toolJson(payload);
  }
);

server.registerTool(
  "remote_docker_follow",
  {
    description: `Follow docker logs on the remote host for a limited time. Response stdout/stderr are capped at 5000 chars; set save_to_file=true (requires ALPHAFROG_DEBUG_LOG_SAVE_DIR) to persist up to 1MB to disk.`,
    inputSchema: {
      env: envSchema,
      container: z.string().default(""),
      follow_seconds: z.number().int().optional().nullable(),
      tail: z.number().int().optional().nullable(),
      grep: z.string().optional().nullable(),
      timestamps: z.boolean().optional().nullable(),
      max_bytes: z.number().int().optional().nullable(),
      save_to_file: z.boolean().optional().nullable(),
    },
  },
  async ({ env, container, follow_seconds, tail, grep, timestamps, max_bytes, save_to_file }) => {
    if (!container?.trim()) {
      return toolJson({ ok: false, error: "container 参数不能为空" });
    }
    const resolved = resolveEnvToHost(env);
    if ("error" in resolved) {
      return toolJson({ ok: false, error: resolved.error });
    }
    const saveToFile = save_to_file === true;
    const followVal = clampInt(follow_seconds, 15, 1, 300);
    const tailVal = clampInt(tail, 200, 1, 10000);
    const ts = timestamps ?? true;
    const args = [...dockerCmd(), "logs", "-f", `--tail=${tailVal}`];
    if (ts) args.push("--timestamps");
    args.push(container.trim());
    const fetchMaxBytes = saveToFile ? SAVE_MAX_BYTES : (max_bytes ?? 50000);
    let result = await runSsh(resolved.host, args, {
      timeoutSeconds: followVal,
      maxBytes: fetchMaxBytes,
    });
    if (grep) {
      result = { ...result, stdout: filterOutput(result.stdout, grep) };
    }
    const inputArgs = {
      env,
      container,
      follow_seconds,
      tail,
      grep,
      timestamps,
      max_bytes,
      save_to_file,
    };
    const payload = await finalizeDockerLogResult("remote_docker_follow", inputArgs, result, resolved.host, {
      saveToFile,
    });
    return toolJson(payload);
  }
);

server.registerTool(
  "remote_pg_query",
  {
    description: `Execute a read-only SELECT query against the alphafrog PostgreSQL database.
Only alphafrog_* tables are allowed. Outer LIMIT in SQL is kept when <= 100; values above 100 are capped to 100. If no outer LIMIT is present, LIMIT 100 is appended. OFFSET is preserved.`,
    inputSchema: {
      env: envSchema,
      sql: z.string(),
    },
  },
  async ({ env, sql }) => {
    if (env !== "test" && env !== "prod") {
      return toolJson({ ok: false, error: "env 必须为 test 或 prod" });
    }
    const rejection = validateSql(sql);
    if (rejection) {
      return toolJson({ ok: false, error: rejection });
    }
    const dsnKey = `ALPHAFROG_PG_${env.toUpperCase()}_DSN`;
    const dsn = process.env[dsnKey];
    if (!dsn) {
      return toolJson({ ok: false, error: pgConfigErrorMessage(env) });
    }

    const { sql: safeSql, effectiveLimit } = applyRowLimit(sql);

    const client = new pg.Client({ connectionString: dsn });
    try {
      await client.connect();
      const res = await client.query(safeSql);
      const columns = res.fields.map((f) => f.name);
      const rows = res.rows.map((row) => columns.map((c) => row[c]));
      return toolJson({
        ok: true,
        columns,
        rows,
        row_count: rows.length,
        truncated: rows.length >= effectiveLimit,
      });
    } catch (e) {
      console.error("[remote_pg_query]", e);
      return toolJson({
        ok: false,
        error: "数据库查询执行失败，详情请查看 MCP 服务端日志",
      });
    } finally {
      await client.end().catch(() => {});
    }
  }
);

const dataOperationSchema = z.enum(DATA_OPERATIONS);

server.registerTool(
  "remote_agent_data_query",
  {
    description: `Read-only query of agent-related host data directories mounted into containers (e.g. agent_datasets, agent_workspaces).
env: "test" or "prod". operation: list | tree | find_name | find_content | stat | du | head | tail | read_range.
relative_path is relative to the configured data root. Content read and find_content are blocked for agent-configs and sensitive filenames.`,
    inputSchema: {
      env: envSchema,
      operation: dataOperationSchema.default("list"),
      relative_path: z.string().optional().nullable(),
      pattern: z.string().optional().nullable(),
      query: z.string().optional().nullable(),
      max_depth: z.number().int().optional().nullable(),
      limit: z.number().int().optional().nullable(),
      head_lines: z.number().int().optional().nullable(),
      tail_lines: z.number().int().optional().nullable(),
      start_line: z.number().int().optional().nullable(),
      line_count: z.number().int().optional().nullable(),
      max_file_bytes: z.number().int().optional().nullable(),
      max_bytes: z.number().int().optional().nullable(),
      timeout_seconds: z.number().int().optional().nullable(),
    },
  },
  async ({
    env,
    operation,
    relative_path,
    pattern,
    query,
    max_depth,
    limit,
    head_lines,
    tail_lines,
    start_line,
    line_count,
    max_file_bytes,
    max_bytes,
    timeout_seconds,
  }) => {
    const resolved = resolveEnvToHost(env);
    if ("error" in resolved) {
      return toolJson({ ok: false, error: resolved.error });
    }

    const rootResult = dataRootForEnv(env);
    if ("error" in rootResult) {
      return toolJson({ ok: false, error: rootResult.error });
    }

    const relResult = validateRelativePath(relative_path);
    if ("error" in relResult) {
      return toolJson({ ok: false, error: relResult.error });
    }
    const relativePath = relResult.path;

    const op = operation as DataOperation;
    const defaultDepth = defaultMaxDepthForOperation(op);
    const maxDepth = clampInt(max_depth, defaultDepth, 1, 8);
    const limitVal = clampInt(limit, 200, 1, 1000);
    const timeoutVal = clampInt(timeout_seconds, 10, 1, 60);
    const outputMaxBytes = clampInt(max_bytes, 20000, 1024, 200000);

    if (op === "find_name") {
      const patternErr = validateFindNamePattern(pattern);
      if (patternErr) {
        return toolJson({ ok: false, error: patternErr });
      }
    }

    if (op === "find_content") {
      const queryErr = validateContentQuery(query);
      if (queryErr) {
        return toolJson({ ok: false, error: queryErr });
      }
      if (isAgentConfigsRelativePath(relativePath)) {
        return toolJson({
          ok: false,
          error: "agent-configs 目录下不允许按内容搜索，请换用其他目录",
        });
      }
    }

    if (READ_CONTENT_OPERATIONS.has(op) && op !== "find_content") {
      const readErr = validateReadContentPath(relativePath);
      if (readErr) {
        return toolJson({ ok: false, error: readErr });
      }
    }

    let script: string;
    try {
      script = buildDataQueryRemoteScript({
        operation: op,
        dataRoot: rootResult.path,
        relativePath,
        maxDepth,
        limit: limitVal,
        pattern,
        query,
        headLines: clampInt(head_lines, 80, 1, 500),
        tailLines: clampInt(tail_lines, 80, 1, 500),
        startLine: clampInt(start_line, 1, 1, 1_000_000),
        lineCount: clampInt(line_count, 100, 1, 500),
        maxFileBytes: clampInt(max_file_bytes, 1048576, 1024, 10485760),
        readMaxBytes: outputMaxBytes,
      });
    } catch (e) {
      console.error("[remote_agent_data_query] build script", e);
      return toolJson({ ok: false, error: "无法构造远程查询命令" });
    }

    const raw = await runSsh(resolved.host, ["bash", "-lc", script], {
      timeoutSeconds: timeoutVal,
      maxBytes: outputMaxBytes,
    });

    const mappedErr = mapRemoteDataQueryError(raw.stderr, raw.stdout);
    if (mappedErr) {
      return toolJson({ ok: false, error: mappedErr, duration_ms: raw.duration_ms });
    }

    const redacted = redactSshToolResult(raw, resolved.host);
    const effectiveOk = raw.ok || (op === "find_content" && raw.exit_code === 1 && !raw.timed_out);
    return toolJson({
      ...redacted,
      ok: effectiveOk,
      operation: op,
      relative_path: relativePath,
    });
  }
);

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error("alphafrog-debug-mcp (Node) running on stdio");
}

main().catch((err) => {
  console.error("Server error:", err);
  process.exit(1);
});
