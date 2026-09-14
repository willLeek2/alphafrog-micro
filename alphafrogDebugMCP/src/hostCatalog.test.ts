import assert from "node:assert/strict";
import test from "node:test";

import {
  allSshHosts,
  dataRootForTarget,
  defaultHostsFilePath,
  listPublicTargets,
  loadHostCatalog,
  redactHosts,
  repoPathForTarget,
  resolveTarget,
  type CatalogDeps,
} from "./hostCatalog.js";

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

test("默认清单路径位于用户主目录下的 .alphafrog", () => {
  assert.equal(defaultHostsFilePath("/Users/demo"), "/Users/demo/.alphafrog/debug-mcp-hosts.json");
});

test("显式清单文件加载目标，list 不返回 ssh_host", () => {
  const filePath = "/secret/hosts.json";
  const catalog = loadHostCatalog(
    makeDeps({
      env: { ALPHAFROG_DEBUG_HOSTS_FILE: filePath },
      files: {
        [filePath]: JSON.stringify({
          targets: [
            { id: "test", label: "测试环境", ssh_host: "secret-alias-a" },
            { id: "prod", ssh_host: "secret-alias-b", data_root: "/data" },
          ],
        }),
      },
    })
  );
  assert.equal(catalog.source, "file");
  assert.equal(catalog.targets.length, 2);
  const publicList = listPublicTargets(catalog, () => undefined);
  assert.deepEqual(
    publicList.map((t) => t.id),
    ["test", "prod"]
  );
  assert.equal(JSON.stringify(publicList).includes("secret-alias"), false);
  assert.deepEqual(publicList[1].capabilities, ["docker", "git", "agent_data"]);
});

test("显式清单文件不存在时返回泛化错误，不含路径", () => {
  const catalog = loadHostCatalog(
    makeDeps({
      env: { ALPHAFROG_DEBUG_HOSTS_FILE: "/no/such/hosts.json" },
      files: {},
    })
  );
  assert.equal(catalog.loadError, "远程目标清单尚未在服务端配置完成");
  assert.equal((catalog.loadError ?? "").includes("/no/such"), false);
});

test("清单 JSON 无效时返回格式错误", () => {
  const filePath = "/secret/hosts.json";
  const catalog = loadHostCatalog(
    makeDeps({
      env: { ALPHAFROG_DEBUG_HOSTS_FILE: filePath },
      files: { [filePath]: "{not-json" },
    })
  );
  assert.equal(catalog.loadError, "远程目标清单格式无效");
});

test("重复 id 或非法 ssh_host 视为格式无效", () => {
  const filePath = "/secret/hosts.json";
  const dup = loadHostCatalog(
    makeDeps({
      env: { ALPHAFROG_DEBUG_HOSTS_FILE: filePath },
      files: {
        [filePath]: JSON.stringify({
          targets: [
            { id: "test", ssh_host: "host-a" },
            { id: "test", ssh_host: "host-b" },
          ],
        }),
      },
    })
  );
  assert.equal(dup.loadError, "远程目标清单格式无效");

  const badHost = loadHostCatalog(
    makeDeps({
      env: { ALPHAFROG_DEBUG_HOSTS_FILE: filePath },
      files: {
        [filePath]: JSON.stringify({
          targets: [{ id: "test", ssh_host: "bad host" }],
        }),
      },
    })
  );
  assert.equal(badHost.loadError, "远程目标清单格式无效");
});

test("默认路径文件存在时优先于旧版环境变量", () => {
  const home = "/Users/demo";
  const defaultPath = `${home}/.alphafrog/debug-mcp-hosts.json`;
  const catalog = loadHostCatalog(
    makeDeps({
      home,
      env: {
        ALPHAFROG_DEBUG_SSH_HOST_TEST: "legacy-host",
      },
      files: {
        [defaultPath]: JSON.stringify({
          targets: [{ id: "staging", label: "预发", ssh_host: "file-host" }],
        }),
      },
    })
  );
  assert.equal(catalog.source, "file");
  assert.deepEqual(
    catalog.targets.map((t) => t.id),
    ["staging"]
  );
  assert.equal(catalog.targets[0].sshHost, "file-host");
});

test("没有清单文件时回退到 test/prod 环境变量，list 仍不暴露 host", () => {
  const catalog = loadHostCatalog(
    makeDeps({
      env: {
        ALPHAFROG_DEBUG_SSH_HOST_TEST: "legacy-test",
        ALPHAFROG_DEBUG_SSH_HOST_PROD: "legacy-prod",
        ALPHAFROG_DEBUG_SSH_HOSTS: "legacy-test,legacy-prod",
        ALPHAFROG_PG_TEST_DSN: "postgresql://x",
      },
    })
  );
  assert.equal(catalog.source, "legacy_env");
  assert.equal(catalog.targets.length, 2);
  const publicList = listPublicTargets(catalog, (key) =>
    key === "ALPHAFROG_PG_TEST_DSN" ? "postgresql://x" : undefined
  );
  assert.equal(JSON.stringify(publicList).includes("legacy-"), false);
  assert.ok(publicList[0].capabilities.includes("pg"));
  assert.equal(publicList[1].capabilities.includes("pg"), false);
});

test("resolveTarget 按 id 解析，未知 id 不回传 host", () => {
  const catalog = loadHostCatalog(
    makeDeps({
      env: { ALPHAFROG_DEBUG_HOSTS_FILE: "/secret/hosts.json" },
      files: {
        "/secret/hosts.json": JSON.stringify({
          targets: [{ id: "test", ssh_host: "secret-alias-a" }],
        }),
      },
    })
  );
  const ok = resolveTarget(catalog, "test");
  assert.equal("target" in ok, true);
  if ("target" in ok) {
    assert.equal(ok.target.sshHost, "secret-alias-a");
  }
  const missing = resolveTarget(catalog, "prod");
  assert.equal("error" in missing, true);
  if ("error" in missing) {
    assert.equal(missing.error.includes("secret-alias"), false);
  }
});

test("redactHosts 替换全部已知 host", () => {
  const text = redactHosts("ssh failed on secret-alias-a via secret-alias-b", [
    "secret-alias-a",
    "secret-alias-b",
  ]);
  assert.equal(text.includes("secret-alias"), false);
  assert.match(text, /远程主机已隐藏/);
});

test("repoPath 与 dataRoot 优先使用清单字段", () => {
  const catalog = loadHostCatalog(
    makeDeps({
      env: { ALPHAFROG_DEBUG_HOSTS_FILE: "/secret/hosts.json" },
      files: {
        "/secret/hosts.json": JSON.stringify({
          targets: [
            {
              id: "test",
              ssh_host: "h1",
              repo_path: "~/repo",
              data_root: "/data",
            },
          ],
        }),
      },
    })
  );
  const resolved = resolveTarget(catalog, "test");
  assert.equal("target" in resolved, true);
  if ("target" in resolved) {
    const repo = repoPathForTarget(resolved.target, null, () => undefined);
    assert.deepEqual(repo, { path: "~/repo" });
    const data = dataRootForTarget(resolved.target, () => undefined);
    assert.deepEqual(data, { path: "/data" });
  }
});

test("allSshHosts 返回内部 host 列表供脱敏使用", () => {
  const catalog = loadHostCatalog(
    makeDeps({
      env: {
        ALPHAFROG_DEBUG_SSH_HOST_TEST: "h-test",
      },
    })
  );
  assert.deepEqual(allSshHosts(catalog), ["h-test"]);
});
