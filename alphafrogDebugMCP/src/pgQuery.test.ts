import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import test from "node:test";
import type { ChildProcess } from "node:child_process";

import {
  listPublicTargets,
  loadHostCatalog,
  resolveTarget,
  type CatalogDeps,
} from "./hostCatalog.js";
import {
  buildSshLocalForwardArgs,
  openSshLocalForward,
  parsePgDsn,
  pgClientConfig,
  PG_NOT_CONFIGURED,
  PG_QUERY_FAILED,
  planPgConnect,
  publicPayloadHasSecrets,
} from "./pgQuery.js";

function makeDeps(overrides: {
  env?: Record<string, string | undefined>;
  files?: Record<string, string>;
  home?: string;
}): CatalogDeps {
  const home = overrides.home ?? "/tmp/fake-home";
  const files = overrides.files ?? {};
  const envMap = overrides.env ?? {};
  return {
    home,
    env: (key) => envMap[key],
    exists: (filePath) => Object.prototype.hasOwnProperty.call(files, filePath),
    readFile: (filePath) => {
      if (!Object.prototype.hasOwnProperty.call(files, filePath)) {
        throw new Error("missing");
      }
      return files[filePath];
    },
  };
}

function catalogWith(targets: unknown[]) {
  return loadHostCatalog(
    makeDeps({
      env: { ALPHAFROG_DEBUG_HOSTS_FILE: "/secret/hosts.json" },
      files: {
        "/secret/hosts.json": JSON.stringify({ targets }),
      },
    })
  );
}

test("缺 DSN 返回尚未配置，不含 host", () => {
  const catalog = catalogWith([{ id: "beta_cn", ssh_host: "secret-alias-a", pg_via_ssh: true }]);
  const plan = planPgConnect("beta_cn", catalog, () => undefined);
  assert.deepEqual(plan, { error: PG_NOT_CONFIGURED });
  assert.equal(JSON.stringify(plan).includes("secret-alias"), false);
});

test("无 pg_via_ssh 时走直连 DSN", () => {
  const catalog = catalogWith([{ id: "prod", ssh_host: "secret-alias-b" }]);
  const dsn = "postgresql://u:p@203.0.113.10:5432/alphafrog";
  const plan = planPgConnect("prod", catalog, (key) =>
    key === "ALPHAFROG_PG_PROD_DSN" ? dsn : undefined
  );
  assert.deepEqual(plan, { mode: "direct", connectionString: dsn });
  const cfg = pgClientConfig(plan as { mode: "direct"; connectionString: string });
  assert.equal(cfg.connectionString, dsn);
});

test("pg_via_ssh 时解析 DSN 并拼 ssh -L，Client 连本机端口", () => {
  const catalog = catalogWith([
    { id: "beta_cn", label: "beta环境(CN)", ssh_host: "secret-alias-a", pg_via_ssh: true },
  ]);
  const dsn = "postgresql://beta_user:s3cret@127.0.0.1:5432/alphafrog";
  const plan = planPgConnect("beta_cn", catalog, (key) =>
    key === "ALPHAFROG_PG_BETA_CN_DSN" ? dsn : undefined
  );
  assert.equal("mode" in plan && plan.mode === "ssh", true);
  if (!("mode" in plan) || plan.mode !== "ssh") return;
  assert.equal(plan.sshHost, "secret-alias-a");
  assert.equal(plan.remoteHost, "127.0.0.1");
  assert.equal(plan.remotePort, 5432);
  assert.equal(plan.user, "beta_user");
  assert.equal(plan.database, "alphafrog");

  const args = buildSshLocalForwardArgs({
    sshHost: plan.sshHost,
    localPort: 15432,
    remoteHost: plan.remoteHost,
    remotePort: plan.remotePort,
    sshConfig: "/Users/demo/.ssh/config",
  });
  assert.deepEqual(args.slice(0, 3), ["ssh", "-F", "/Users/demo/.ssh/config"]);
  assert.equal(args.includes("-N"), true);
  assert.equal(args.includes("ExitOnForwardFailure=yes"), true);
  assert.equal(args.includes("127.0.0.1:15432:127.0.0.1:5432"), true);
  assert.equal(args[args.length - 1], "secret-alias-a");

  const cfg = pgClientConfig(plan, 15432);
  assert.equal(cfg.host, "127.0.0.1");
  assert.equal(cfg.port, 15432);
  assert.equal(cfg.user, "beta_user");
  assert.equal(cfg.password, "s3cret");
  assert.equal(cfg.database, "alphafrog");
  assert.equal("connectionString" in cfg, false);
});

test("list_remote_targets 有 pg 能力但不含 pg_via_ssh、ssh 别名、DSN", () => {
  const catalog = catalogWith([
    {
      id: "beta_cn",
      label: "beta环境(CN)",
      ssh_host: "secret-alias-a",
      pg_via_ssh: true,
    },
  ]);
  const dsn = "postgresql://beta_user:s3cret@127.0.0.1:5432/alphafrog";
  const publicList = listPublicTargets(catalog, (key) =>
    key === "ALPHAFROG_PG_BETA_CN_DSN" ? dsn : undefined
  );
  assert.deepEqual(publicList[0].capabilities, ["docker", "git", "pg"]);
  const text = JSON.stringify(publicList);
  assert.equal(text.includes("secret-alias"), false);
  assert.equal(text.includes("pg_via_ssh"), false);
  assert.equal(text.includes("s3cret"), false);
  assert.equal(text.includes("127.0.0.1"), false);
  assert.equal(text.includes("postgresql://"), false);
});

test("pg_via_ssh 解析后 resolveTarget 带内部标记，公开 JSON 仍不含该字段", () => {
  const catalog = catalogWith([
    { id: "beta_cn", ssh_host: "secret-alias-a", pg_via_ssh: true },
  ]);
  const resolved = resolveTarget(catalog, "beta_cn");
  assert.equal("target" in resolved && resolved.target.pgViaSsh, true);
  const publicList = listPublicTargets(catalog, () => undefined);
  assert.equal(JSON.stringify(publicList).includes("pg_via_ssh"), false);
  assert.equal(JSON.stringify(publicList).includes("pgViaSsh"), false);
});

test("parsePgDsn 支持 postgresql URL", () => {
  const parsed = parsePgDsn("postgresql://u:p%40ss@172.27.49.47:5432/alphafrog");
  assert.equal("error" in parsed, false);
  if ("error" in parsed) return;
  assert.equal(parsed.host, "172.27.49.47");
  assert.equal(parsed.port, 5432);
  assert.equal(parsed.user, "u");
  assert.equal(parsed.password, "p@ss");
  assert.equal(parsed.database, "alphafrog");
});

test("查询失败公开文案不含连接细节", () => {
  const payload = { ok: false, error: PG_QUERY_FAILED };
  assert.equal(
    publicPayloadHasSecrets(payload, [
      "secret-alias-a",
      "pg_via_ssh",
      "/Users/demo/.ssh/config",
      "postgresql://",
      "s3cret",
    ]),
    false
  );
});

test("openSshLocalForward 用分配的端口拼 -L，Client 侧端口与之相同", async () => {
  const spawned: string[][] = [];
  const fakeChild = new EventEmitter() as ChildProcess;
  Object.assign(fakeChild, {
    stderr: new EventEmitter(),
    exitCode: null,
    killed: false,
    kill: () => {
      setImmediate(() => fakeChild.emit("exit", 0, "SIGTERM"));
      return true;
    },
  });
  const handle = await openSshLocalForward({
    sshHost: "secret-alias-a",
    remoteHost: "127.0.0.1",
    remotePort: 5432,
    sshConfig: "/hidden/ssh/config",
    allocatePort: async () => 18001,
    spawnFn: ((_cmd, args) => {
      spawned.push(args as string[]);
      return fakeChild;
    }) as typeof import("node:child_process").spawn,
    waitForPort: async () => {},
    logError: () => {},
  });
  assert.equal(handle.localPort, 18001);
  assert.equal(spawned[0].includes("127.0.0.1:18001:127.0.0.1:5432"), true);
  assert.equal(spawned[0].includes("/hidden/ssh/config"), true);
  const cfg = pgClientConfig(
    {
      mode: "ssh",
      sshHost: "secret-alias-a",
      remoteHost: "127.0.0.1",
      remotePort: 5432,
      user: "u",
      password: "p",
      database: "alphafrog",
    },
    handle.localPort
  );
  assert.equal(cfg.port, 18001);
  assert.equal(cfg.host, "127.0.0.1");
  await handle.close();
});
