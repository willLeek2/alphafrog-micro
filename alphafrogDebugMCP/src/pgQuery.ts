/**
 * PostgreSQL 只读查询：直连 DSN，或经目标 ssh_host 做本地转发后再连。
 * 工具返回不含 ssh 别名、config 路径、DSN 与转发端口。
 */
import { spawn, type ChildProcess } from "node:child_process";
import net from "node:net";
import type { ClientConfig } from "pg";

import {
  type HostCatalog,
  redactHosts,
  resolveTarget,
} from "./hostCatalog.js";

export const PG_NOT_CONFIGURED = "该目标尚未配置数据库连接";
export const PG_QUERY_FAILED = "数据库查询执行失败，详情请查看 MCP 服务端日志";

const REMOTE_HOST_RE = /^[A-Za-z0-9._-]+$/;
const TUNNEL_WAIT_MS = 15_000;
const PG_CONNECT_TIMEOUT_MS = 15_000;

export type ParsedPgDsn = {
  host: string;
  port: number;
  user: string;
  password: string;
  database: string;
};

export type PgConnectPlan =
  | { mode: "direct"; connectionString: string }
  | {
      mode: "ssh";
      sshHost: string;
      remoteHost: string;
      remotePort: number;
      user: string;
      password: string;
      database: string;
    };

export type SshTunnelHandle = {
  localPort: number;
  close: () => Promise<void>;
};

export function pgDsnKey(envId: string): string {
  return `ALPHAFROG_PG_${envId.toUpperCase()}_DSN`;
}

export function parsePgDsn(dsn: string): ParsedPgDsn | { error: string } {
  let url: URL;
  try {
    url = new URL(dsn);
  } catch {
    return { error: PG_NOT_CONFIGURED };
  }
  if (url.protocol !== "postgres:" && url.protocol !== "postgresql:") {
    return { error: PG_NOT_CONFIGURED };
  }
  const host = decodeURIComponent(url.hostname || "").trim();
  if (!host || !REMOTE_HOST_RE.test(host)) {
    return { error: PG_NOT_CONFIGURED };
  }
  const port = url.port ? Number.parseInt(url.port, 10) : 5432;
  if (!Number.isInteger(port) || port <= 0 || port > 65535) {
    return { error: PG_NOT_CONFIGURED };
  }
  const database = decodeURIComponent(url.pathname.replace(/^\//, "")).split("/")[0] ?? "";
  if (!database) {
    return { error: PG_NOT_CONFIGURED };
  }
  return {
    host,
    port,
    user: decodeURIComponent(url.username || ""),
    password: decodeURIComponent(url.password || ""),
    database,
  };
}

export function planPgConnect(
  envId: string,
  catalog: HostCatalog,
  envGetter: (key: string) => string | undefined = (k) => process.env[k]
): PgConnectPlan | { error: string } {
  const dsn = (envGetter(pgDsnKey(envId)) ?? "").trim();
  if (!dsn) {
    return { error: PG_NOT_CONFIGURED };
  }
  const resolved = resolveTarget(catalog, envId);
  if (!("error" in resolved) && resolved.target.pgViaSsh) {
    const parsed = parsePgDsn(dsn);
    if ("error" in parsed) {
      return parsed;
    }
    return {
      mode: "ssh",
      sshHost: resolved.target.sshHost,
      remoteHost: parsed.host,
      remotePort: parsed.port,
      user: parsed.user,
      password: parsed.password,
      database: parsed.database,
    };
  }
  return { mode: "direct", connectionString: dsn };
}

export function buildSshLocalForwardArgs(opts: {
  sshHost: string;
  localPort: number;
  remoteHost: string;
  remotePort: number;
  sshConfig?: string;
  extraArgs?: string[];
}): string[] {
  const args: string[] = ["ssh"];
  const sshConfig = opts.sshConfig?.trim();
  if (sshConfig) {
    args.push("-F", sshConfig);
  }
  if (opts.extraArgs?.length) {
    args.push(...opts.extraArgs);
  }
  args.push(
    "-N",
    "-o",
    "ExitOnForwardFailure=yes",
    "-o",
    "BatchMode=yes",
    "-L",
    `127.0.0.1:${opts.localPort}:${opts.remoteHost}:${opts.remotePort}`,
    opts.sshHost
  );
  return args;
}

export function pgClientConfig(plan: PgConnectPlan, localPort?: number): ClientConfig {
  if (plan.mode === "direct") {
    return { connectionString: plan.connectionString, connectionTimeoutMillis: PG_CONNECT_TIMEOUT_MS };
  }
  if (localPort === undefined) {
    throw new Error("ssh local port required");
  }
  return {
    host: "127.0.0.1",
    port: localPort,
    user: plan.user,
    password: plan.password,
    database: plan.database,
    connectionTimeoutMillis: PG_CONNECT_TIMEOUT_MS,
  };
}

export function allocateLoopbackPort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.listen(0, "127.0.0.1", () => {
      const addr = server.address();
      if (!addr || typeof addr === "string") {
        server.close();
        reject(new Error("allocate port failed"));
        return;
      }
      const port = addr.port;
      server.close((err) => {
        if (err) reject(err);
        else resolve(port);
      });
    });
    server.on("error", reject);
  });
}

export function waitForLocalPort(
  port: number,
  child: ChildProcess,
  timeoutMs: number = TUNNEL_WAIT_MS
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const onChildExit = (code: number | null) => {
      reject(new Error(`ssh exited ${code ?? "null"}`));
    };
    child.once("exit", onChildExit);

    const tryOnce = () => {
      if (child.exitCode !== null) {
        return;
      }
      const socket = net.connect({ host: "127.0.0.1", port }, () => {
        child.off("exit", onChildExit);
        socket.end();
        resolve();
      });
      socket.on("error", () => {
        socket.destroy();
        if (Date.now() > deadline) {
          child.off("exit", onChildExit);
          reject(new Error("ssh tunnel wait timeout"));
          return;
        }
        setTimeout(tryOnce, 50);
      });
    };
    tryOnce();
  });
}

function stopChild(child: ChildProcess): Promise<void> {
  if (child.exitCode !== null || child.killed) {
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      resolve();
    }, 2000);
    child.once("exit", () => {
      clearTimeout(timer);
      resolve();
    });
    child.kill("SIGTERM");
  });
}

export type OpenSshTunnelOpts = {
  sshHost: string;
  remoteHost: string;
  remotePort: number;
  sshConfig?: string;
  extraArgs?: string[];
  knownSshHosts?: string[];
  timeoutMs?: number;
  spawnFn?: typeof spawn;
  allocatePort?: () => Promise<number>;
  waitForPort?: (port: number, child: ChildProcess, timeoutMs: number) => Promise<void>;
  logError?: (message: string) => void;
};

export async function openSshLocalForward(opts: OpenSshTunnelOpts): Promise<SshTunnelHandle> {
  const localPort = await (opts.allocatePort ?? allocateLoopbackPort)();
  const args = buildSshLocalForwardArgs({
    sshHost: opts.sshHost,
    localPort,
    remoteHost: opts.remoteHost,
    remotePort: opts.remotePort,
    sshConfig: opts.sshConfig,
    extraArgs: opts.extraArgs,
  });
  const spawnFn = opts.spawnFn ?? spawn;
  const child = spawnFn(args[0], args.slice(1), { stdio: ["ignore", "ignore", "pipe"] });
  const redactList = [opts.sshHost, opts.remoteHost, ...(opts.knownSshHosts ?? [])];
  const logError = opts.logError ?? ((message: string) => console.error(`[remote_pg_query] ${message}`));
  child.stderr?.on("data", (buf: Buffer) => {
    logError(redactHosts(buf.toString(), redactList));
  });

  try {
    await (opts.waitForPort ?? waitForLocalPort)(localPort, child, opts.timeoutMs ?? TUNNEL_WAIT_MS);
  } catch (e) {
    await stopChild(child);
    throw e;
  }

  return {
    localPort,
    close: () => stopChild(child),
  };
}

/** 给单测核对：公开 JSON 不得包含这些内部字段名或连接细节。 */
export function publicPayloadHasSecrets(
  payload: unknown,
  secrets: string[]
): boolean {
  const text = JSON.stringify(payload);
  return secrets.some((s) => s && text.includes(s));
}
