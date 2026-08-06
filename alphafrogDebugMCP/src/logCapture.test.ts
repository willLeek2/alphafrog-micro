import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import {
  buildLogBody,
  buildLogFileName,
  computeLogFileHash,
  formatSaveFileContent,
  formatTimestampForFilename,
  resolveLogSaveDir,
  saveLogToFile,
  truncateResponseFields,
  truncateTextByBytes,
} from "./logCapture.js";

test("formatTimestampForFilename 生成文件名安全时间戳", () => {
  const ts = formatTimestampForFilename(new Date("2026-06-24T01:26:00.123Z"));
  assert.equal(ts, "20260624T012600123Z");
});

test("computeLogFileHash 取 sha256 前 8 位", () => {
  const invokedAt = new Date("2026-06-24T01:26:00.123Z");
  const hash = computeLogFileHash(invokedAt, "remote_docker_logs", { env: "test", container: "c1" }, "nonce1");
  assert.match(hash, /^[0-9a-f]{8}$/);
  const hash2 = computeLogFileHash(invokedAt, "remote_docker_logs", { env: "test", container: "c1" }, "nonce2");
  assert.notEqual(hash, hash2);
});

test("buildLogFileName 格式为 时间-hash8.txt", () => {
  const invokedAt = new Date("2026-06-24T01:26:00.123Z");
  const name = buildLogFileName(invokedAt, "remote_docker_logs", { env: "test" }, "abc");
  assert.match(name, /^20260624T012600123Z-[0-9a-f]{8}\.txt$/);
});

test("formatSaveFileContent 包含元信息与空两行后的日志", () => {
  const invokedAt = new Date("2026-06-24T01:26:00.123Z");
  const content = formatSaveFileContent(invokedAt, "remote_docker_logs", { env: "test" }, "line1\nline2");
  assert.match(content, /^调用时间: 2026-06-24T01:26:00\.123Z\n/);
  assert.match(content, /工具名: remote_docker_logs\n/);
  assert.match(content, /"env": "test"/);
  assert.ok(content.includes("\n\nline1\nline2"));
});

test("truncateTextByBytes 在 UTF-8 边界安全截断", () => {
  const text = "你好世界";
  const { text: truncated, truncated: wasTruncated } = truncateTextByBytes(text, 7);
  assert.equal(wasTruncated, true);
  assert.ok(Buffer.from(truncated, "utf8").length <= 7);
});

test("truncateResponseFields 超过 5000 字符时返回提示", () => {
  const long = "x".repeat(6000);
  const result = truncateResponseFields(long, "");
  assert.equal(result.stdout.length, 5000);
  assert.equal(result.response_truncated, true);
  assert.ok(result.response_truncation_hint?.includes("save_to_file=true"));
});

test("resolveLogSaveDir 未配置时返回错误", () => {
  const result = resolveLogSaveDir("");
  assert.ok("error" in result);
});

test("saveLogToFile 写入后可读", () => {
  const dir = mkdtempSync(path.join(tmpdir(), "alphafrog-log-save-"));
  try {
    const save = saveLogToFile(dir, "test.txt", "hello");
    assert.equal(save.ok, true);
    if (save.ok) {
      assert.equal(readFileSync(save.filePath, "utf8"), "hello");
    }
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("buildLogBody 合并 stdout 与 stderr", () => {
  const body = buildLogBody("out", "err");
  assert.match(body, /out/);
  assert.match(body, /--- stderr ---/);
  assert.match(body, /err/);
});
