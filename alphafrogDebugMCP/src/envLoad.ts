/**
 * MCP 进程启动时加载本机 dotenv：先共享文件，再仓库根 .env。
 * dotenv 默认不覆盖进程里已有变量，harness 注入的键优先。
 */
import { existsSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

import dotenv from "dotenv";

import { expandHomePath } from "./hostCatalog.js";

export const DEFAULT_SHARED_ENV_BASENAME = path.join(".alphafrog", "debug-mcp.env");

export type EnvLoadDeps = {
  env: (key: string) => string | undefined;
  exists: (filePath: string) => boolean;
  home: string;
  repoRoot: string;
  loadFile: (filePath: string) => void;
};

export function defaultSharedEnvPath(home: string = os.homedir()): string {
  return path.join(home, DEFAULT_SHARED_ENV_BASENAME);
}

export function defaultRepoRootFromModule(moduleDir: string = path.dirname(fileURLToPath(import.meta.url))): string {
  return path.resolve(moduleDir, "..", "..");
}

function defaultDeps(moduleDir?: string): EnvLoadDeps {
  return {
    env: (key) => process.env[key],
    exists: (filePath) => existsSync(filePath),
    home: os.homedir(),
    repoRoot: defaultRepoRootFromModule(moduleDir),
    loadFile: (filePath) => {
      dotenv.config({ path: filePath, quiet: true });
    },
  };
}

/** 返回按顺序加载的 dotenv 路径：共享文件（或 ALPHAFROG_DEBUG_DOTENV_PATH），然后仓库根 .env。 */
export function dotenvFilesToLoad(deps: EnvLoadDeps): string[] {
  const files: string[] = [];
  const custom = (deps.env("ALPHAFROG_DEBUG_DOTENV_PATH") ?? "").trim();
  if (custom) {
    const expanded = expandHomePath(custom, deps.home);
    if (deps.exists(expanded)) {
      files.push(expanded);
    }
  } else {
    const shared = defaultSharedEnvPath(deps.home);
    if (deps.exists(shared)) {
      files.push(shared);
    }
  }
  const repoEnv = path.join(deps.repoRoot, ".env");
  if (deps.exists(repoEnv) && !files.includes(repoEnv)) {
    files.push(repoEnv);
  }
  return files;
}

export function loadDebugMcpEnv(partial?: Partial<EnvLoadDeps> & { moduleDir?: string }): string[] {
  const { moduleDir, ...rest } = partial ?? {};
  const deps: EnvLoadDeps = { ...defaultDeps(moduleDir), ...rest };
  const files = dotenvFilesToLoad(deps);
  for (const filePath of files) {
    deps.loadFile(filePath);
  }
  return files;
}
