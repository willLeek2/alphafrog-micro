/**
 * 远程目标清单：从本机保密文件（或旧版环境变量）加载 ssh host。
 * 对外只暴露逻辑 id / label / 能力列表，不把真实 host 返回给调用方。
 */
import { existsSync, readFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";

export const TARGET_ID_RE = /^[a-z][a-z0-9_]{0,31}$/;
export const SSH_HOST_RE = /^[A-Za-z0-9._-]+$/;

export const DEFAULT_HOSTS_FILE_BASENAME = path.join(".alphafrog", "debug-mcp-hosts.json");

export type HostTarget = {
  id: string;
  label: string;
  sshHost: string;
  repoPath?: string;
  dataRoot?: string;
};

export type HostCatalog = {
  source: "file" | "legacy_env";
  targets: HostTarget[];
  /** 给调用方看的泛化错误，不含文件路径与 host。 */
  loadError?: string;
};

export type PublicTarget = {
  id: string;
  label: string;
  capabilities: string[];
};

export type CatalogDeps = {
  env: (key: string) => string | undefined;
  readFile: (filePath: string) => string;
  exists: (filePath: string) => boolean;
  home: string;
  logError?: (message: string) => void;
};

function defaultDeps(): CatalogDeps {
  return {
    env: (key) => process.env[key],
    readFile: (filePath) => readFileSync(filePath, "utf8"),
    exists: (filePath) => existsSync(filePath),
    home: os.homedir(),
    logError: (message) => console.error(`[alphafrog-debug-mcp] ${message}`),
  };
}

export function defaultHostsFilePath(home: string = os.homedir()): string {
  return path.join(home, DEFAULT_HOSTS_FILE_BASENAME);
}

export function expandHomePath(raw: string, home: string): string {
  const trimmed = raw.trim();
  if (trimmed === "~") return home;
  if (trimmed.startsWith("~/")) return path.join(home, trimmed.slice(2));
  return trimmed;
}

function envList(env: CatalogDeps["env"], key: string): string[] {
  const raw = (env(key) ?? "").trim();
  if (!raw) return [];
  return raw.split(",").map((s) => s.trim()).filter(Boolean);
}

function optionalNonEmpty(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  return trimmed ? trimmed : undefined;
}

function parseTargetsArray(raw: unknown, logError?: CatalogDeps["logError"]): HostTarget[] | { error: string } {
  if (!Array.isArray(raw)) {
    logError?.("hosts catalog invalid: targets is not an array");
    return { error: "远程目标清单格式无效" };
  }

  const targets: HostTarget[] = [];
  const seen = new Set<string>();

  for (const item of raw) {
    if (!item || typeof item !== "object") {
      logError?.("hosts catalog invalid: target entry is not an object");
      return { error: "远程目标清单格式无效" };
    }
    const rec = item as Record<string, unknown>;
    const id = typeof rec.id === "string" ? rec.id.trim() : "";
    if (!TARGET_ID_RE.test(id)) {
      logError?.("hosts catalog invalid: target id format");
      return { error: "远程目标清单格式无效" };
    }
    if (seen.has(id)) {
      logError?.("hosts catalog invalid: duplicate target id");
      return { error: "远程目标清单格式无效" };
    }
    const sshHost = typeof rec.ssh_host === "string" ? rec.ssh_host.trim() : "";
    if (!SSH_HOST_RE.test(sshHost)) {
      logError?.("hosts catalog invalid: ssh_host format");
      return { error: "远程目标清单格式无效" };
    }
    const label = optionalNonEmpty(rec.label) ?? id;
    const repoPath = optionalNonEmpty(rec.repo_path);
    const dataRoot = optionalNonEmpty(rec.data_root);
    if (repoPath && /[\0\n\r]/.test(repoPath)) {
      logError?.("hosts catalog invalid: repo_path contains illegal characters");
      return { error: "远程目标清单格式无效" };
    }
    if (dataRoot && /[\0\n\r]/.test(dataRoot)) {
      logError?.("hosts catalog invalid: data_root contains illegal characters");
      return { error: "远程目标清单格式无效" };
    }
    seen.add(id);
    targets.push({
      id,
      label,
      sshHost,
      ...(repoPath ? { repoPath } : {}),
      ...(dataRoot ? { dataRoot } : {}),
    });
  }

  return targets;
}

function parseHostsFileContent(text: string, logError?: CatalogDeps["logError"]): HostCatalog {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    logError?.("hosts catalog invalid: json parse failed");
    return { source: "file", targets: [], loadError: "远程目标清单格式无效" };
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    logError?.("hosts catalog invalid: root is not an object");
    return { source: "file", targets: [], loadError: "远程目标清单格式无效" };
  }
  const targets = parseTargetsArray((parsed as { targets?: unknown }).targets, logError);
  if (!Array.isArray(targets)) {
    return { source: "file", targets: [], loadError: targets.error };
  }
  return { source: "file", targets };
}

function loadFromFile(filePath: string, deps: CatalogDeps): HostCatalog {
  if (!deps.exists(filePath)) {
    deps.logError?.("hosts catalog file missing");
    return { source: "file", targets: [], loadError: "远程目标清单尚未在服务端配置完成" };
  }
  let text: string;
  try {
    text = deps.readFile(filePath);
  } catch {
    deps.logError?.("hosts catalog file unreadable");
    return { source: "file", targets: [], loadError: "远程目标清单尚未在服务端配置完成" };
  }
  return parseHostsFileContent(text, deps.logError);
}

function loadLegacyEnv(deps: CatalogDeps): HostCatalog {
  const allowed = envList(deps.env, "ALPHAFROG_DEBUG_SSH_HOSTS");
  const targets: HostTarget[] = [];
  for (const id of ["test", "prod"] as const) {
    const host = (deps.env(`ALPHAFROG_DEBUG_SSH_HOST_${id.toUpperCase()}`) ?? "").trim();
    if (!host) continue;
    if (!SSH_HOST_RE.test(host)) {
      deps.logError?.("legacy ssh host format invalid");
      continue;
    }
    if (allowed.length > 0 && !allowed.includes(host)) {
      deps.logError?.("legacy ssh host not in allowlist");
      continue;
    }
    const repoPath = optionalNonEmpty(deps.env(`ALPHAFROG_DEBUG_REPO_PATH_${id.toUpperCase()}`));
    const dataRoot = optionalNonEmpty(deps.env(`ALPHAFROG_DEBUG_DATA_ROOT_${id.toUpperCase()}`));
    targets.push({
      id,
      label: id === "test" ? "测试环境" : "生产环境",
      sshHost: host,
      ...(repoPath ? { repoPath } : {}),
      ...(dataRoot ? { dataRoot } : {}),
    });
  }
  return { source: "legacy_env", targets };
}

/** 加载远程目标清单。显式文件或默认文件存在时以文件为准，不再回退到环境变量里的 host。 */
export function loadHostCatalog(partial?: Partial<CatalogDeps>): HostCatalog {
  const deps: CatalogDeps = { ...defaultDeps(), ...partial };
  const explicit = (deps.env("ALPHAFROG_DEBUG_HOSTS_FILE") ?? "").trim();
  if (explicit) {
    return loadFromFile(expandHomePath(explicit, deps.home), deps);
  }
  const defaultPath = defaultHostsFilePath(deps.home);
  if (deps.exists(defaultPath)) {
    return loadFromFile(defaultPath, deps);
  }
  return loadLegacyEnv(deps);
}

export function resolveTarget(
  catalog: HostCatalog,
  env: string
): { target: HostTarget } | { error: string } {
  if (catalog.loadError) {
    return { error: catalog.loadError };
  }
  const id = (env ?? "").trim();
  if (!TARGET_ID_RE.test(id)) {
    return { error: "未知远程目标，请先调用 list_remote_targets" };
  }
  const target = catalog.targets.find((t) => t.id === id);
  if (!target) {
    return { error: "未知远程目标，请先调用 list_remote_targets" };
  }
  return { target };
}

export function allSshHosts(catalog: HostCatalog): string[] {
  return catalog.targets.map((t) => t.sshHost).filter(Boolean);
}

/** 从文本中去掉已知 ssh host，避免工具返回里带出真实主机名。 */
export function redactHosts(text: string, hosts: string[]): string {
  if (!text || hosts.length === 0) return text;
  const sorted = [...new Set(hosts.filter(Boolean))].sort((a, b) => b.length - a.length);
  let out = text;
  for (const host of sorted) {
    out = out.split(host).join("[远程主机已隐藏]");
  }
  return out;
}

export function listPublicTargets(
  catalog: HostCatalog,
  envGetter: (key: string) => string | undefined = (k) => process.env[k]
): PublicTarget[] {
  return catalog.targets.map((t) => {
    const upper = t.id.toUpperCase();
    const capabilities: string[] = ["docker", "git"];
    if ((envGetter(`ALPHAFROG_PG_${upper}_DSN`) ?? "").trim()) {
      capabilities.push("pg");
    }
    const redisContainer = (envGetter(`ALPHAFROG_REDIS_CONTAINER_${upper}`) ?? "").trim();
    const redisPassword = envGetter(`ALPHAFROG_REDIS_PASSWORD_${upper}`) ?? "";
    if (redisContainer && redisPassword) {
      capabilities.push("redis");
    }
    const dataRoot =
      t.dataRoot || (envGetter(`ALPHAFROG_DEBUG_DATA_ROOT_${upper}`) ?? "").trim();
    if (dataRoot) {
      capabilities.push("agent_data");
    }
    return { id: t.id, label: t.label, capabilities };
  });
}

export function repoPathForTarget(
  target: HostTarget,
  repoPathOverride: string | null | undefined,
  envGetter: (key: string) => string | undefined = (k) => process.env[k]
): { path: string } | { error: string } {
  if (repoPathOverride?.trim()) {
    return { path: repoPathOverride.trim() };
  }
  const fromTarget = target.repoPath?.trim();
  if (fromTarget) {
    return { path: fromTarget };
  }
  const fromEnv = (envGetter(`ALPHAFROG_DEBUG_REPO_PATH_${target.id.toUpperCase()}`) ?? "").trim();
  if (fromEnv) {
    return { path: fromEnv };
  }
  const fallback = (envGetter("ALPHAFROG_DEBUG_DEFAULT_REPO_PATH") ?? "").trim();
  if (!fallback) {
    return { error: "远程仓库路径尚未在服务端配置完成" };
  }
  return { path: fallback };
}

export function dataRootForTarget(
  target: HostTarget,
  envGetter: (key: string) => string | undefined = (k) => process.env[k]
): { path: string } | { error: string } {
  const fromTarget = target.dataRoot?.trim();
  if (fromTarget) {
    return { path: fromTarget };
  }
  const fromEnv = (envGetter(`ALPHAFROG_DEBUG_DATA_ROOT_${target.id.toUpperCase()}`) ?? "").trim();
  if (!fromEnv) {
    return { error: "该目标尚未配置 agent data 根目录，目前该工具不可用，请咨询人类用户获取信息" };
  }
  return { path: fromEnv };
}
