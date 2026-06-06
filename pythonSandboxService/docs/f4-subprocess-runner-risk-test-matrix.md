# F4 — Sandbox subprocess runner 风险与测试矩阵

Owner: @cursor-bob-mbp · Review: @codex-coder-mbp  
Architecture: 长寿命容器 + 容器内 runner 调度 + 每次 `subprocess.Popen(["python", "-I", "-u", ...])` 执行用户脚本（frog 拍板）

## 风险登记

| ID | 风险 | 严重度 | 缓解 / 验收 |
|----|------|--------|-------------|
| R1 | 容器复用后 **文件残留**，下一 task 读到上一 task 的 dataset/输出 | **P0** | task-scoped workspace；cleanup 失败 **recycle 容器** |
| R2 | 误以为「池化」= **import/变量可复用**（实际每次新子进程） | P1 | 文档 + 测试 T2；性能预期 3–6s 保守 |
| R3 | **`/sandbox/input` 路径** 与模型模板不兼容 | **P0** | bind mount / alias 到 `runs/<task_id>/input`；T5 |
| R4 | **timeout/cancel** 只杀 HTTP 未杀子进程 → 僵尸/占资源 | **P0** | runner 负责 kill process group；T3 |
| R5 | **cleanup 失败** 仍 release 回池 | **P0** | discard/recycle；T4 |
| R6 | **pool 满** 时行为不明（排队 vs 失败 vs 静默变慢） | P1 | 可配置策略 + `queue_wait_ms` / `pool_rejected` 日志；T6 |
| R7 | host **dataset cache TTL** 与容器内清理混淆 | P1 | TTL 仅 `AF_SANDBOX_DATA_DIR`；容器内每 task 同步清理 |
| R8 | 子进程 **fd/临时文件** 泄漏拖垮长寿命容器 | P1 | ulimit + 定期 health check + 异常 recycle |
| R9 | 并发下 **同一容器** 被两个 task 同时使用 | **P0** | acquire 互斥；一容器一 in-flight execution |
| R10 | **恶意/异常代码** 改 `sys.path`/`os.chdir` 影响后续 | P1 | 子进程隔离；父进程/runner 不信任子进程 cwd |

## 必过测试矩阵

| ID | 场景 | 步骤（摘要） | 期望 | 观测 |
|----|------|--------------|------|------|
| T1 | **跨 task 数据隔离** | Task A 写 `runs/A/out.txt`；Task B 读 glob/`open` | B **不可见** A 的文件 | 断言 B stdout/exit；可选 list workspace |
| T2 | **无解释器状态继承** | A: `GLOBAL=1` + `import pandas as pd`；B: `print(GLOBAL)` | B **NameError** 或 GLOBAL 未定义 | exit_code ≠ 0 或输出不含 `1` |
| T3 | **脚本 timeout** | 子进程 `sleep(300)`，task timeout=5s | 5s 内失败；**下一 task 成功** | `run_ms`≈timeout；后续 T1 级成功 |
| T4 | **cleanup 失败 → 回收** | 模拟 cleanup 抛错或 chmod 不可删 | 容器 **discard**，不入池；新 task 用新容器 | log `container_recycled`；无脏读 |
| T5 | **`/sandbox/input` 兼容** | 模板代码 `pd.read_csv('/sandbox/input/<id>/data.csv')` | **成功** 读数据 | exit 0；结果合理 |
| T6 | **pool 耗尽** | 并发 > `max_pool_size` | 行为符合配置（queue/reject）；日志含 **queue_wait_ms** 或 reject 原因 | 无无限 hang |
| T7 | **不同 dataset 连续** | A dataset `ds1`；B dataset `ds2` | B 只读到 ds2 | 同 T1 + 双 dataset id |
| T8 | **monkey patch 不泄漏** | A: `pd.read_csv = lambda *a,**k: "hacked"` | B 的 `pd.read_csv` **正常** | B 输出非 hacked |
| T9 | **cancel 中途** | 长跑 + client cancel | 子进程终止；容器可接下一单 | 同 T3 |
| T10 | **并发 N=max_concurrency** | N 个合法短脚本并行 | 全部成功；p50 duration 在预算内 | 汇总 `run_ms` p50/p90 |

## 建议日志字段（实现验收用）

每次 task 至少一条结构化日志：

- `task_id`, `container_id`, `queue_wait_ms`, `workspace_prepare_ms`, `copy_ms`, `subprocess_run_ms`, `cleanup_ms`, `total_ms`
- `cleanup_ok`, `container_action` (`released` | `recycled`)
- `subprocess_exit_code`, `timeout`, `canceled`

## 与 F1/F2/F3 的接口

| 上游 | 下游 |
|------|------|
| F1 baseline | 提供改造前各段 ms，用于对比 T10 |
| F2 设计 | 必须映射到 R1–R10 的缓解措施 |
| F3 实现 | PR checklist = 本表 T1–T10 全绿或 documented waive |

## 非目标（第一版可不测）

- Interactive kernel 跨 cell 变量保留（方案 B）
- 多租户跨 host 文件权限模型（假定单服务实例 + Docker boundary）
