/**
 * 远程 docker 日志落盘与 MCP 响应截断辅助逻辑。
 */
import { createHash, randomBytes } from "node:crypto";
import { accessSync, constants, mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";

/** 落盘时单次最多保存的日志字节数（1MB）。 */
export const SAVE_MAX_BYTES = 1024 * 1024;

/** 返回给 LLM 的 stdout/stderr 字符上限。 */
export const RESPONSE_CHAR_LIMIT = 5000;

/** MCP 启动时配置的日志落盘目录环境变量名。 */
export const LOG_SAVE_DIR_ENV = "ALPHAFROG_DEBUG_LOG_SAVE_DIR";

const RESPONSE_TRUNCATION_HINT =
  "返回内容已截断至 5000 字符。如需完整日志，请使用 save_to_file=true 将日志落盘到 ALPHAFROG_DEBUG_LOG_SAVE_DIR 指定目录，再读取生成的 txt 文件。";

export type LogSaveDirResult = { dir: string } | { error: string };

export type SaveLogResult =
  | { ok: true; filePath: string; fileName: string }
  | { ok: false; error: string };

export type ResponseTruncationResult = {
  stdout: string;
  stderr: string;
  response_truncated: boolean;
  response_truncation_hint?: string;
};

/** 解析并校验日志落盘目录（需可写）。 */
export function resolveLogSaveDir(envValue: string | undefined = process.env[LOG_SAVE_DIR_ENV]): LogSaveDirResult {
  const dir = envValue?.trim();
  if (!dir) {
    return {
      error: `save_to_file=true 但未配置 ${LOG_SAVE_DIR_ENV}，请在 MCP 启动环境变量中指定日志落盘目录`,
    };
  }
  try {
    mkdirSync(dir, { recursive: true });
    accessSync(dir, constants.W_OK);
    return { dir: path.resolve(dir) };
  } catch {
    return { error: `日志落盘目录不可用或不可写: ${dir}` };
  }
}

/** 将 Date 格式化为文件名安全的时间戳，例如 20260624T012600123Z。 */
export function formatTimestampForFilename(date: Date): string {
  const iso = date.toISOString(); // 2026-06-24T01:26:00.123Z
  const [datePart, timePart] = iso.split("T");
  const timeWithoutZ = timePart.replace("Z", "").replace(/:/g, "").replace(/\./g, "");
  return `${datePart.replace(/-/g, "")}T${timeWithoutZ}Z`;
}

/** 对入参做稳定 JSON 序列化（键排序），便于 hash 计算。 */
export function stableStringify(value: unknown): string {
  if (value === null || typeof value !== "object") {
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) {
    return `[${value.map((item) => stableStringify(item)).join(",")}]`;
  }
  const record = value as Record<string, unknown>;
  const keys = Object.keys(record).sort();
  return `{${keys.map((k) => `${JSON.stringify(k)}:${stableStringify(record[k])}`).join(",")}}`;
}

/** 计算落盘文件名 hash 输入并取 sha256 前 8 位。 */
export function computeLogFileHash(
  invokedAt: Date,
  toolName: string,
  inputArgs: Record<string, unknown>,
  nonce: string
): string {
  const payload = [invokedAt.toISOString(), toolName, stableStringify(inputArgs), nonce].join("\n");
  return createHash("sha256").update(payload, "utf8").digest("hex").slice(0, 8);
}

/** 生成落盘文件名：调用时间-hash前8位.txt。 */
export function buildLogFileName(
  invokedAt: Date,
  toolName: string,
  inputArgs: Record<string, unknown>,
  nonce: string
): string {
  const ts = formatTimestampForFilename(invokedAt);
  const hash8 = computeLogFileHash(invokedAt, toolName, inputArgs, nonce);
  return `${ts}-${hash8}.txt`;
}

/** 按字节上限截断文本（UTF-8 安全）。 */
export function truncateTextByBytes(text: string, maxBytes: number): { text: string; truncated: boolean } {
  if (maxBytes <= 0) {
    return { text: "", truncated: text.length > 0 };
  }
  const buf = Buffer.from(text, "utf8");
  if (buf.length <= maxBytes) {
    return { text, truncated: false };
  }
  let end = maxBytes;
  while (end > 0 && (buf[end] & 0xc0) === 0x80) {
    end -= 1;
  }
  return { text: buf.subarray(0, end).toString("utf8"), truncated: true };
}

/** 组装落盘 txt 的正文：元信息 + 空两行 + 日志内容。 */
export function formatSaveFileContent(
  invokedAt: Date,
  toolName: string,
  inputArgs: Record<string, unknown>,
  logContent: string
): string {
  const header = [
    `调用时间: ${invokedAt.toISOString()}`,
    `工具名: ${toolName}`,
    `入参: ${JSON.stringify(inputArgs, null, 2)}`,
    "",
    "",
  ].join("\n");
  return header + logContent;
}

/** 合并 stdout/stderr 作为落盘日志正文。 */
export function buildLogBody(stdout: string, stderr: string): string {
  const parts: string[] = [];
  if (stdout) {
    parts.push(stdout);
  }
  if (stderr) {
    if (parts.length > 0) {
      parts.push("\n--- stderr ---\n");
    }
    parts.push(stderr);
  }
  return parts.join("");
}

/** 将日志写入落盘目录。 */
export function saveLogToFile(
  saveDir: string,
  fileName: string,
  content: string
): SaveLogResult {
  try {
    const filePath = path.join(saveDir, fileName);
    writeFileSync(filePath, content, "utf8");
    return { ok: true, filePath, fileName };
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    return { ok: false, error: `写入日志文件失败: ${msg}` };
  }
}

/** 对 MCP 响应中的 stdout/stderr 做字符级截断。 */
export function truncateResponseFields(
  stdout: string,
  stderr: string,
  maxChars: number = RESPONSE_CHAR_LIMIT
): ResponseTruncationResult {
  const [outStdout, stdoutTruncated] = truncateChars(stdout, maxChars);
  const [outStderr, stderrTruncated] = truncateChars(stderr, maxChars);
  const response_truncated = stdoutTruncated || stderrTruncated;
  return {
    stdout: outStdout,
    stderr: outStderr,
    response_truncated,
    ...(response_truncated ? { response_truncation_hint: RESPONSE_TRUNCATION_HINT } : {}),
  };
}

function truncateChars(text: string, maxChars: number): [string, boolean] {
  if (text.length <= maxChars) {
    return [text, false];
  }
  return [text.slice(0, maxChars), true];
}

/** 生成一次性随机数，参与 hash 计算。 */
export function generateLogNonce(): string {
  return randomBytes(16).toString("hex");
}
