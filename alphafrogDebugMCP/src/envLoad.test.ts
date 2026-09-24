import assert from "node:assert/strict";
import test from "node:test";

import { defaultSharedEnvPath, dotenvFilesToLoad, loadDebugMcpEnv, type EnvLoadDeps } from "./envLoad.js";

function makeDeps(overrides: {
  env?: Record<string, string | undefined>;
  files?: Record<string, string>;
  home?: string;
  repoRoot?: string;
}): EnvLoadDeps & { loaded: string[] } {
  const home = overrides.home ?? "/tmp/fake-home";
  const files = overrides.files ?? {};
  const envMap = overrides.env ?? {};
  const loaded: string[] = [];
  return {
    home,
    repoRoot: overrides.repoRoot ?? "/repo",
    env: (key) => envMap[key],
    exists: (filePath) => Object.prototype.hasOwnProperty.call(files, filePath),
    loadFile: (filePath) => {
      loaded.push(filePath);
    },
    loaded,
  };
}

test("默认共享 dotenv 路径在 ~/.alphafrog/debug-mcp.env", () => {
  assert.equal(defaultSharedEnvPath("/Users/demo"), "/Users/demo/.alphafrog/debug-mcp.env");
});

test("存在共享文件时先加载共享文件再加载仓库 .env", () => {
  const home = "/Users/demo";
  const shared = `${home}/.alphafrog/debug-mcp.env`;
  const repoEnv = "/repo/.env";
  const deps = makeDeps({
    home,
    repoRoot: "/repo",
    files: {
      [shared]: "ALPHAFROG_PG_BETA_CN_DSN=postgresql://from-shared",
      [repoEnv]: "ALPHAFROG_PG_PROD_DSN=postgresql://from-repo",
    },
  });
  const files = dotenvFilesToLoad(deps);
  assert.deepEqual(files, [shared, repoEnv]);
  loadDebugMcpEnv(deps);
  assert.deepEqual(deps.loaded, [shared, repoEnv]);
});

test("ALPHAFROG_DEBUG_DOTENV_PATH 覆盖共享文件路径，仓库 .env 仍作为回退", () => {
  const custom = "/custom/debug.env";
  const repoEnv = "/repo/.env";
  const deps = makeDeps({
    env: { ALPHAFROG_DEBUG_DOTENV_PATH: custom },
    files: {
      [custom]: "A=1",
      [repoEnv]: "B=2",
      "/Users/demo/.alphafrog/debug-mcp.env": "SHOULD_NOT_LOAD=1",
    },
    home: "/Users/demo",
    repoRoot: "/repo",
  });
  assert.deepEqual(dotenvFilesToLoad(deps), [custom, repoEnv]);
});

test("只有仓库 .env 时仍能加载", () => {
  const repoEnv = "/repo/.env";
  const deps = makeDeps({
    files: { [repoEnv]: "A=1" },
    repoRoot: "/repo",
  });
  assert.deepEqual(dotenvFilesToLoad(deps), [repoEnv]);
});
