import assert from "node:assert/strict";
import test from "node:test";

import {
  buildDockerLogsRemoteArgs,
  normalizeDockerLogTimeArg,
  validateDockerLogTimeWindow,
} from "./dockerLogs.js";

const DOCKER_PREFIX = ["docker"];

test("无时间过滤时默认 --tail=200", () => {
  const result = buildDockerLogsRemoteArgs({
    dockerPrefix: DOCKER_PREFIX,
    container: "my-container",
  });
  assert.equal(result.ok, true);
  if (result.ok) {
    assert.deepEqual(result.args, ["docker", "logs", "--tail=200", "--timestamps", "my-container"]);
    assert.equal(result.tailUsed, "200");
  }
});

test("since 和 until 会生成对应 docker 参数", () => {
  const result = buildDockerLogsRemoteArgs({
    dockerPrefix: DOCKER_PREFIX,
    container: "c1",
    since: "2026-06-24T01:00:00+08:00",
    until: "2026-06-24T01:10:00+08:00",
  });
  assert.equal(result.ok, true);
  if (result.ok) {
    assert.deepEqual(result.args, [
      "docker",
      "logs",
      "--tail=all",
      "--since=2026-06-24T01:00:00+08:00",
      "--until=2026-06-24T01:10:00+08:00",
      "--timestamps",
      "c1",
    ]);
    assert.equal(result.tailUsed, "all");
  }
});

test("指定时间过滤且未显式传 tail 时使用 --tail=all", () => {
  const result = buildDockerLogsRemoteArgs({
    dockerPrefix: DOCKER_PREFIX,
    container: "c1",
    since: "10m",
  });
  assert.equal(result.ok, true);
  if (result.ok) {
    assert.equal(result.tailUsed, "all");
    assert.ok(result.args.includes("--tail=all"));
    assert.ok(result.args.includes("--since=10m"));
  }
});

test("显式传 tail 时尊重用户值", () => {
  const result = buildDockerLogsRemoteArgs({
    dockerPrefix: DOCKER_PREFIX,
    container: "c1",
    since: "2026-06-24T01:00:00+08:00",
    tail: 500,
  });
  assert.equal(result.ok, true);
  if (result.ok) {
    assert.equal(result.tailUsed, "500");
    assert.ok(result.args.includes("--tail=500"));
  }
});

test("空字符串 since 返回中文错误", () => {
  const result = buildDockerLogsRemoteArgs({
    dockerPrefix: DOCKER_PREFIX,
    container: "c1",
    since: "   ",
  });
  assert.equal(result.ok, false);
  if (!result.ok) {
    assert.match(result.error, /since 参数不能为空字符串/);
  }
});

test("无效时间格式返回错误", () => {
  const result = normalizeDockerLogTimeArg("not-a-time", "since");
  assert.equal(result.ok, false);
  if (!result.ok) {
    assert.match(result.error, /格式无效/);
  }
});

test("相对时间格式 since 可接受", () => {
  const result = normalizeDockerLogTimeArg("30m", "since");
  assert.equal(result.ok, true);
  if (result.ok) {
    assert.equal(result.value, "30m");
  }
});

test("since 晚于 until 时返回错误", () => {
  const result = validateDockerLogTimeWindow(
    "2026-06-24T02:00:00+08:00",
    "2026-06-24T01:00:00+08:00"
  );
  assert.equal(result.ok, false);
  if (!result.ok) {
    assert.match(result.error, /since 不能晚于 until/);
  }
});

test("timestamps=false 时不追加 --timestamps", () => {
  const result = buildDockerLogsRemoteArgs({
    dockerPrefix: DOCKER_PREFIX,
    container: "c1",
    timestamps: false,
  });
  assert.equal(result.ok, true);
  if (result.ok) {
    assert.ok(!result.args.includes("--timestamps"));
  }
});
