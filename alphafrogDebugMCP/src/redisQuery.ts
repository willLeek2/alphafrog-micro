/**
 * 远程 Redis 只读查询：构造 docker exec redis-cli 命令、解析输出、响应截断。
 */

/** scan_keys 默认/最大 limit。 */
export const SCAN_KEYS_DEFAULT_LIMIT = 100;
export const SCAN_KEYS_MAX_LIMIT = 500;
export const SCAN_KEYS_MAX_OFFSET = 10000;

/** get_values 最大 key 数量。 */
export const GET_VALUES_MAX_KEYS = 50;

/** 集合/列表/zset 单次最多返回元素数。 */
export const COLLECTION_MAX_ITEMS = 100;

/** MCP 返回 JSON 字符上限。 */
export const REDIS_RESPONSE_CHAR_LIMIT = 2000;

export const REDIS_RESPONSE_TRUNCATION_HINT =
  "Redis 查询结果已截断至 2000 字符，请缩小 pattern、降低 limit、减少 keys 或改查更具体的 key，避免消耗过多上下文。";

const KEY_PATTERN_MAX_LEN = 256;
const REDIS_KEY_MAX_LEN = 512;
const CONTAINER_NAME_RE = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;

export type RedisEnv = "test" | "prod";

export type RedisOperation = "scan_keys" | "get_values";

export type RedisConfigResult =
  | { container: string; password: string }
  | { error: string };

export type ScanKeysInput = {
  pattern?: string | null;
  limit?: number | null;
  offset?: number | null;
};

export type GetValuesInput = {
  keys?: string[] | null;
};

export type RedisKeyEntry = {
  key: string;
  type: string;
  exists: boolean;
  value?: unknown;
  ttl_seconds?: number;
  truncated?: boolean;
  error?: string;
};

export type ScanKeysResult = {
  ok: true;
  operation: "scan_keys";
  pattern: string;
  offset: number;
  limit: number;
  keys: string[];
  key_count: number;
  has_more: boolean;
};

export type GetValuesResult = {
  ok: true;
  operation: "get_values";
  entries: RedisKeyEntry[];
  key_count: number;
};

export type RedisQueryError = { ok: false; error: string };

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

/** 解析 shell 风格命令前缀（如 docker / redis-cli）。 */
export function parseCommandPrefix(raw: string | undefined, fallback: string): string[] {
  if (!raw?.trim()) {
    return fallback.split(/\s+/).filter(Boolean);
  }
  return raw.trim().split(/\s+/).filter(Boolean);
}

/** 读取指定 env 的 Redis 容器名与密码。 */
export function redisConfigForEnv(
  env: RedisEnv,
  envGetter: (key: string) => string | undefined = (k) => process.env[k]
): RedisConfigResult {
  const upper = env.toUpperCase();
  const container = (envGetter(`ALPHAFROG_REDIS_CONTAINER_${upper}`) ?? "").trim();
  const password = envGetter(`ALPHAFROG_REDIS_PASSWORD_${upper}`) ?? "";

  if (!container) {
    return {
      error:
        env === "test"
          ? "所选测试环境尚未在服务端完成 Redis 容器配置"
          : "所选生产环境尚未在服务端完成 Redis 容器配置",
    };
  }
  if (!CONTAINER_NAME_RE.test(container)) {
    return { error: "服务端 Redis 容器名配置格式无效" };
  }
  if (!password) {
    return {
      error:
        env === "test"
          ? "所选测试环境尚未在服务端完成 Redis 认证配置"
          : "所选生产环境尚未在服务端完成 Redis 认证配置",
    };
  }
  return { container, password };
}

/** 校验 scan pattern（禁止空 pattern，避免全库扫描失控）。 */
export function validateScanPattern(pattern: string | null | undefined): string | null {
  if (pattern === null || pattern === undefined) {
    return "scan_keys 必须提供 pattern 参数";
  }
  const trimmed = pattern.trim();
  if (!trimmed) {
    return "pattern 不能为空";
  }
  if (trimmed.length > KEY_PATTERN_MAX_LEN) {
    return `pattern 过长（最多 ${KEY_PATTERN_MAX_LEN} 字符）`;
  }
  if (trimmed.includes("\0")) {
    return "pattern 包含非法字符";
  }
  return null;
}

/** 校验单个 Redis key。 */
export function validateRedisKey(key: string): string | null {
  const trimmed = key.trim();
  if (!trimmed) {
    return "key 不能为空";
  }
  if (trimmed.length > REDIS_KEY_MAX_LEN) {
    return `key 过长（最多 ${REDIS_KEY_MAX_LEN} 字符）`;
  }
  if (/[\s\r\n\0]/.test(trimmed)) {
    return "key 包含非法空白字符";
  }
  return null;
}

/** 校验 get_values 的 keys 列表。 */
export function validateGetValuesKeys(keys: string[] | null | undefined): string | null {
  if (!keys || keys.length === 0) {
    return "get_values 必须提供非空 keys 数组";
  }
  if (keys.length > GET_VALUES_MAX_KEYS) {
    return `keys 数量不能超过 ${GET_VALUES_MAX_KEYS}`;
  }
  for (const key of keys) {
    const err = validateRedisKey(key);
    if (err) {
      return err;
    }
  }
  return null;
}

export function normalizeScanParams(input: ScanKeysInput): {
  pattern: string;
  limit: number;
  offset: number;
} {
  return {
    pattern: (input.pattern ?? "").trim(),
    limit: clampInt(input.limit, SCAN_KEYS_DEFAULT_LIMIT, 1, SCAN_KEYS_MAX_LIMIT),
    offset: clampInt(input.offset, 0, 0, SCAN_KEYS_MAX_OFFSET),
  };
}

/** bash 单引号转义。 */
export function bashSingleQuote(value: string): string {
  return `'${value.replace(/'/g, `'\\''`)}'`;
}

export type BuildRemoteScriptInput = {
  dockerPrefix: string[];
  redisCliPrefix: string[];
  container: string;
  password: string;
};

function dockerExecRedisCliBase(input: BuildRemoteScriptInput): string {
  const docker = input.dockerPrefix.join(" ");
  const cli = input.redisCliPrefix.join(" ");
  const container = bashSingleQuote(input.container);
  const password = bashSingleQuote(input.password);
  return `${docker} exec ${container} ${cli} --raw --no-auth-warning -a ${password}`;
}

/** 构造 scan_keys 远程 bash 脚本（单行 pipeline）。 */
export function buildScanKeysRemoteScript(
  input: BuildRemoteScriptInput & ScanKeysInput
): { ok: true; script: string } | { ok: false; error: string } {
  const patternErr = validateScanPattern(input.pattern);
  if (patternErr) {
    return { ok: false, error: patternErr };
  }
  const { pattern, limit, offset } = normalizeScanParams(input);
  const base = dockerExecRedisCliBase(input);
  const patternLit = bashSingleQuote(pattern);
  // awk：跳过 offset 个匹配 key，再取 limit 个；额外多取 1 个用于判断 has_more
  const script = [
    `${base} --scan --pattern ${patternLit} 2>/dev/null | awk -v offset=${offset} -v limit=${limit} '`,
    "  NR > offset { print; count++; if (count > limit) exit }",
    "'",
  ].join("\n");
  return { ok: true, script };
}

/** 构造 get_values 远程 bash 脚本，输出结构化分隔块便于解析。 */
export function buildGetValuesRemoteScript(
  input: BuildRemoteScriptInput & GetValuesInput
): { ok: true; script: string } | { ok: false; error: string } {
  const keysErr = validateGetValuesKeys(input.keys);
  if (keysErr) {
    return { ok: false, error: keysErr };
  }
  const keys = input.keys!.map((k) => k.trim());
  const base = dockerExecRedisCliBase(input);
  const maxItems = COLLECTION_MAX_ITEMS;

  const lines: string[] = ["set -euo pipefail"];
  for (const key of keys) {
    const keyLit = bashSingleQuote(key);
    lines.push(`echo '__KEY__'`);
    lines.push(`printf '%s\\n' ${keyLit}`);
    lines.push(`TYPE=$(${base} TYPE ${keyLit} 2>/dev/null || echo none)`);
    lines.push(`echo '__TYPE__'`);
    lines.push(`printf '%s\\n' "$TYPE"`);
    lines.push(`TTL=$(${base} TTL ${keyLit} 2>/dev/null || echo -2)`);
    lines.push(`echo '__TTL__'`);
    lines.push(`printf '%s\\n' "$TTL"`);
    lines.push(`case "$TYPE" in`);
    lines.push(`  string)`);
    lines.push(`    echo '__DATA__'`);
    lines.push(`    ${base} GET ${keyLit} 2>/dev/null || true`);
    lines.push(`    ;;`);
    lines.push(`  hash)`);
    lines.push(`    echo '__DATA__'`);
    lines.push(`    ${base} HGETALL ${keyLit} 2>/dev/null | head -n $(( ${maxItems} * 2 )) || true`);
    lines.push(`    ;;`);
    lines.push(`  list)`);
    lines.push(`    echo '__DATA__'`);
    lines.push(`    ${base} LRANGE ${keyLit} 0 $(( ${maxItems} - 1 )) 2>/dev/null || true`);
    lines.push(`    ;;`);
    lines.push(`  set)`);
    lines.push(`    echo '__DATA__'`);
    lines.push(`    ${base} SMEMBERS ${keyLit} 2>/dev/null | head -n ${maxItems} || true`);
    lines.push(`    ;;`);
    lines.push(`  zset)`);
    lines.push(`    echo '__DATA__'`);
    lines.push(`    ${base} ZRANGE ${keyLit} 0 $(( ${maxItems} - 1 )) WITHSCORES 2>/dev/null || true`);
    lines.push(`    ;;`);
    lines.push(`  none)`);
    lines.push(`    echo '__DATA__'`);
    lines.push(`    ;;`);
    lines.push(`  *)`);
    lines.push(`    echo '__DATA__'`);
    lines.push(`    echo "__UNSUPPORTED_TYPE__:$TYPE"`);
    lines.push(`    ;;`);
    lines.push(`esac`);
    lines.push(`echo '__END__'`);
  }

  return { ok: true, script: lines.join("\n") };
}

/** 解析 scan_keys 输出；若行数 > limit 则 has_more=true 并去掉最后一行。 */
export function parseScanKeysOutput(
  stdout: string,
  pattern: string,
  offset: number,
  limit: number
): ScanKeysResult {
  const lines = stdout
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter(Boolean);
  const hasMore = lines.length > limit;
  const keys = hasMore ? lines.slice(0, limit) : lines;
  return {
    ok: true,
    operation: "scan_keys",
    pattern,
    offset,
    limit,
    keys,
    key_count: keys.length,
    has_more: hasMore,
  };
}

function parseHashLines(lines: string[]): Record<string, string> {
  const out: Record<string, string> = {};
  for (let i = 0; i + 1 < lines.length; i += 2) {
    out[lines[i]] = lines[i + 1];
  }
  return out;
}

function parseZsetLines(lines: string[]): Array<{ member: string; score: string }> {
  const out: Array<{ member: string; score: string }> = [];
  for (let i = 0; i + 1 < lines.length; i += 2) {
    out.push({ member: lines[i], score: lines[i + 1] });
  }
  return out;
}

function buildValueFromType(type: string, dataLines: string[]): unknown {
  switch (type) {
    case "string":
      return dataLines.join("\n");
    case "hash":
      return parseHashLines(dataLines);
    case "list":
      return dataLines;
    case "set":
      return dataLines;
    case "zset":
      return parseZsetLines(dataLines);
    case "none":
      return null;
    default:
      if (dataLines.length === 1 && dataLines[0].startsWith("__UNSUPPORTED_TYPE__:")) {
        return null;
      }
      return dataLines.join("\n");
  }
}

/** 解析 get_values 结构化输出。 */
export function parseGetValuesOutput(stdout: string): GetValuesResult {
  const entries: RedisKeyEntry[] = [];
  const lines = stdout.split(/\r?\n/);
  let i = 0;

  while (i < lines.length) {
    if (lines[i] !== "__KEY__") {
      i += 1;
      continue;
    }
    i += 1;
    const key = (lines[i] ?? "").trim();
    i += 1;
    if (lines[i] !== "__TYPE__") continue;
    i += 1;
    const type = (lines[i] ?? "none").trim() || "none";
    i += 1;
    let ttl: number | undefined;
    if (lines[i] === "__TTL__") {
      i += 1;
      const ttlRaw = parseInt((lines[i] ?? "-2").trim(), 10);
      if (Number.isFinite(ttlRaw) && ttlRaw >= 0) {
        ttl = ttlRaw;
      }
      i += 1;
    }
    const dataLines: string[] = [];
    if (lines[i] === "__DATA__") {
      i += 1;
      while (i < lines.length && lines[i] !== "__END__") {
        dataLines.push(lines[i]);
        i += 1;
      }
    }
    if (lines[i] === "__END__") {
      i += 1;
    }

    const exists = type !== "none";
    const entry: RedisKeyEntry = {
      key,
      type,
      exists,
    };
    if (ttl !== undefined) {
      entry.ttl_seconds = ttl;
    }
    if (exists) {
      entry.value = buildValueFromType(type, dataLines);
      if (
        (type === "hash" && dataLines.length >= COLLECTION_MAX_ITEMS * 2) ||
        (type === "list" && dataLines.length >= COLLECTION_MAX_ITEMS) ||
        (type === "set" && dataLines.length >= COLLECTION_MAX_ITEMS) ||
        (type === "zset" && dataLines.length >= COLLECTION_MAX_ITEMS * 2)
      ) {
        entry.truncated = true;
      }
      if (type !== "none" && type !== "string" && type !== "hash" && type !== "list" && type !== "set" && type !== "zset") {
        entry.error = `不支持的 Redis 类型: ${type}`;
      }
    }
    entries.push(entry);
  }

  return {
    ok: true,
    operation: "get_values",
    entries,
    key_count: entries.length,
  };
}

/** 对 Redis 查询 payload 做 2k 字符截断。 */
export function truncateRedisPayload(
  payload: ScanKeysResult | GetValuesResult,
  maxChars: number = REDIS_RESPONSE_CHAR_LIMIT
): Record<string, unknown> {
  const full = JSON.stringify(payload);
  if (full.length <= maxChars) {
    return payload as unknown as Record<string, unknown>;
  }
  return {
    ok: payload.ok,
    operation: payload.operation,
    response_truncated: true,
    original_char_length: full.length,
    result_preview: full.slice(0, maxChars),
    response_truncation_hint: REDIS_RESPONSE_TRUNCATION_HINT,
  };
}
