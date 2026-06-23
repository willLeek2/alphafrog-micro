import assert from "node:assert/strict";
import test from "node:test";

import {
  buildGetValuesRemoteScript,
  buildScanKeysRemoteScript,
  COLLECTION_MAX_ITEMS,
  GET_VALUES_MAX_KEYS,
  normalizeScanParams,
  parseGetValuesOutput,
  parseScanKeysOutput,
  REDIS_RESPONSE_CHAR_LIMIT,
  REDIS_RESPONSE_TRUNCATION_HINT,
  redisConfigForEnv,
  truncateRedisPayload,
  validateGetValuesKeys,
  validateScanPattern,
} from "./redisQuery.js";

const DOCKER_PREFIX = ["docker"];
const REDIS_CLI_PREFIX = ["redis-cli"];

const baseInput = {
  dockerPrefix: DOCKER_PREFIX,
  redisCliPrefix: REDIS_CLI_PREFIX,
  container: "alphafrog-redis",
  password: "test-pass",
};

test("redisConfigForEnv 缺少容器名时返回中文错误", () => {
  const result = redisConfigForEnv("test", () => undefined);
  assert.equal("error" in result, true);
  if ("error" in result) {
    assert.match(result.error, /测试环境.*Redis 容器/);
  }
});

test("redisConfigForEnv 缺少密码时返回中文错误", () => {
  const result = redisConfigForEnv("prod", (key) =>
    key === "ALPHAFROG_REDIS_CONTAINER_PROD" ? "alphafrog-redis" : undefined
  );
  assert.equal("error" in result, true);
  if ("error" in result) {
    assert.match(result.error, /生产环境.*Redis 认证/);
  }
});

test("validateScanPattern 拒绝空 pattern", () => {
  assert.match(validateScanPattern("   ")!, /不能为空/);
});

test("normalizeScanParams 会 clamp limit 和 offset", () => {
  const params = normalizeScanParams({ pattern: "agent:*", limit: 9999, offset: 99999 });
  assert.equal(params.limit, 500);
  assert.equal(params.offset, 10000);
});

test("buildScanKeysRemoteScript 生成 docker exec redis-cli --scan", () => {
  const result = buildScanKeysRemoteScript({
    ...baseInput,
    pattern: "agent:run:*",
    limit: 10,
    offset: 5,
  });
  assert.equal(result.ok, true);
  if (result.ok) {
    assert.match(result.script, /docker exec 'alphafrog-redis'/);
    assert.match(result.script, /redis-cli --raw --no-auth-warning -a 'test-pass'/);
    assert.match(result.script, /--scan --pattern 'agent:run:\*'/);
    assert.match(result.script, /offset=5/);
    assert.match(result.script, /limit=10/);
  }
});

test("parseScanKeysOutput 识别 has_more", () => {
  const stdout = ["k1", "k2", "k3"].join("\n");
  const parsed = parseScanKeysOutput(stdout, "agent:*", 0, 2);
  assert.equal(parsed.key_count, 2);
  assert.deepEqual(parsed.keys, ["k1", "k2"]);
  assert.equal(parsed.has_more, true);
});

test("validateGetValuesKeys 限制最大 key 数", () => {
  const keys = Array.from({ length: GET_VALUES_MAX_KEYS + 1 }, (_, i) => `k${i}`);
  assert.match(validateGetValuesKeys(keys)!, /不能超过/);
});

test("buildGetValuesRemoteScript 包含 TYPE/GET 分支", () => {
  const result = buildGetValuesRemoteScript({
    ...baseInput,
    keys: ["agent:run:abc", "fetch:task:debug:1"],
  });
  assert.equal(result.ok, true);
  if (result.ok) {
    assert.match(result.script, /TYPE/);
    assert.match(result.script, /GET 'agent:run:abc'/);
    assert.match(result.script, /HGETALL/);
    assert.match(result.script, /LRANGE/);
    assert.match(result.script, /SMEMBERS/);
    assert.match(result.script, /ZRANGE/);
    assert.match(result.script, new RegExp(`head -n ${COLLECTION_MAX_ITEMS}`));
  }
});

test("parseGetValuesOutput 解析 string 与 hash", () => {
  const stdout = [
    "__KEY__",
    "my:key",
    "__TYPE__",
    "string",
    "__TTL__",
    "3600",
    "__DATA__",
    "hello",
    "__END__",
    "__KEY__",
    "my:hash",
    "__TYPE__",
    "hash",
    "__TTL__",
    "-1",
    "__DATA__",
    "field1",
    "value1",
    "field2",
    "value2",
    "__END__",
  ].join("\n");
  const parsed = parseGetValuesOutput(stdout);
  assert.equal(parsed.key_count, 2);
  assert.equal(parsed.entries[0].type, "string");
  assert.equal(parsed.entries[0].value, "hello");
  assert.equal(parsed.entries[0].ttl_seconds, 3600);
  assert.equal(parsed.entries[1].type, "hash");
  assert.deepEqual(parsed.entries[1].value, { field1: "value1", field2: "value2" });
});

test("truncateRedisPayload 超过 2k 时返回 preview", () => {
  const bigValue = "x".repeat(3000);
  const payload = parseGetValuesOutput(
    ["__KEY__", "k", "__TYPE__", "string", "__TTL__", "-1", "__DATA__", bigValue, "__END__"].join("\n")
  );
  const truncated = truncateRedisPayload(payload, REDIS_RESPONSE_CHAR_LIMIT);
  assert.equal(truncated.response_truncated, true);
  assert.equal(truncated.response_truncation_hint, REDIS_RESPONSE_TRUNCATION_HINT);
  assert.ok((truncated.original_char_length as number) > REDIS_RESPONSE_CHAR_LIMIT);
  assert.equal((truncated.result_preview as string).length, REDIS_RESPONSE_CHAR_LIMIT);
});

test("truncateRedisPayload 小结果不截断", () => {
  const payload = parseScanKeysOutput("k1\nk2", "agent:*", 0, 10);
  const result = truncateRedisPayload(payload);
  assert.equal(result.response_truncated, undefined);
  assert.deepEqual(result.keys, ["k1", "k2"]);
});
