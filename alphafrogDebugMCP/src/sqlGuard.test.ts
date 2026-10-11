import assert from "node:assert/strict";
import test from "node:test";

import { validateSql } from "./sqlGuard.js";

test("alphafrog_ 业务表可以通过", () => {
  assert.equal(validateSql("SELECT id FROM alphafrog_agent_run"), null);
});

test("pg_stat_replication 只读查询可以通过", () => {
  assert.equal(validateSql("SELECT * FROM pg_stat_replication"), null);
  assert.equal(validateSql("SELECT * FROM pg_catalog.pg_stat_replication"), null);
  assert.equal(validateSql('SELECT * FROM "pg_stat_replication"'), null);
});

test("其他系统视图仍然拒绝", () => {
  assert.match(validateSql("SELECT * FROM pg_stat_wal_receiver") ?? "", /pg_stat_wal_receiver/);
  assert.match(validateSql("SELECT * FROM pg_class") ?? "", /pg_class/);
  assert.match(validateSql("SELECT * FROM pg_catalog.pg_authid") ?? "", /pg_authid/);
});

test("非 SELECT 与危险关键字仍然拒绝", () => {
  assert.match(validateSql("UPDATE alphafrog_agent_run SET id = 1") ?? "", /Only SELECT/);
  assert.match(validateSql("SELECT * FROM alphafrog_agent_run; DROP TABLE alphafrog_agent_run") ?? "", /Dangerous keyword/);
});
