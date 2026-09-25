# 阶段 5A 无界面验收补充判定

这份脚本在现有 `agent_run_sse_load_test.py` 跑完后读取它的独立输出目录，并核对同一时间窗的服务端只读父子执行证据。它不会创建 Run、发送取消或写入 Beta；创建与超时取消仍由现有无界面执行器负责。每个场景单独运行一次，要求 `--max-fail-ratio 0`，不要把准备失败的模型样本算作通过。

在 `test_scripts` 目录下运行，解释器固定为 `/Users/frog_wch/miniforge3/envs/alphafrog/bin/python`：

如果验收环境已给当前执行者授权只读 PostgreSQL 连接，先把连接串放入进程环境变量 `AF_STAGE5A_PG_DSN`。测试**开始前**运行：

```bash
/Users/frog_wch/miniforge3/envs/alphafrog/bin/python \
  agent-v1p0/collect_stage5a_child_evidence.py start \
  --output <独立证据目录>/server_window_start.json
```

跑完原有无界面执行器后，再运行：

```bash
/Users/frog_wch/miniforge3/envs/alphafrog/bin/python \
  agent-v1p0/collect_stage5a_child_evidence.py collect \
  --run-dir <本次 headless 输出目录> \
  --window-receipt <独立证据目录>/server_window_start.json \
  --output <独立证据目录>/child_evidence.json
```

采集器只执行只读事务里的 `SELECT`，根 Run ID 从本次 `run_manifest.json` 读取，数据库连接缺失、窗口未覆盖整个测试或根 Run 查不到时退出码为 `2`，不生成可用证据。连接异常正文不写入输出。它不替代原有 `--server-evidence-file` 的部署、路由和配置证据；那份文件仍由原执行器读取。

最后运行判定器：

```bash
/Users/frog_wch/miniforge3/envs/alphafrog/bin/python \
  agent-v1p0/stage5a_child_run_evidence.py \
  --run-dir <本次 headless 输出目录> \
  --scenario single_child \
  --scenario-id <run_manifest.json 中的场景名> \
  --child-evidence-file <独立证据目录>/child_evidence.json \
  --output <独立证据目录>/stage5a_evidence.json
```

`--scenario` 另可选 `out_of_order`、`wait_timeout`。标准输出只写一条 JSON，包含 `status` 和结果文件位置；结果文件保存根 Run、子 Run、创建意图、等待组、运行事件类型、逐项通过检查和缺失或失败原因。退出码：`0` 通过、`1` 行为不符合、`2` 证据不足、`3` 输入文件损坏。

## 三个真实样本

| 场景 | 父请求与受控动作 | 判定重点 |
| --- | --- | --- |
| `single_child` | “请启动一个子代理，让它调用 `executePython` 计算 `6*7`，等待实际结果，再报告计算值；父代理不要自己代算。”父子模型和 Sandbox 均真实执行。 | 独立子 Run 完成、等待结果与父最终回答均含 `42`，并有创建意图、待投递号和恢复通知。 |
| `out_of_order` | 父启动两个子 Run，分别计算 `6*7` 和 `1+2+…+10`。通过限定的验收控制暂缓第一个子 Run 的真实 Sandbox 结果，让第二个先完成，然后放行。 | 第二个子 Run 先终态，但父的多子等待结果仍按请求顺序排列。控制证据须说明暂缓的是第一个子的真实 Sandbox 结果。 |
| `wait_timeout` | 子 Run 的真实 Sandbox 结果保持暂缓，超过第一次 `waitForSubAgent` 的截止时间后再放行；父再次等待。 | 第一次返回 `WAIT_TIMEOUT`，第二次返回终态结果；父等待期间节点和协调许可均已归还。 |

乱序和等待超时的受控样本使用父请求上下文里的 `childAcceptanceControls` 指定子级结果控制。每个条目写一条受限控制行编号 `acceptanceControlId`，以及父模型本次 `spawnSubAgent` 调用的完整身份：`planGeneration`（计划代际）、`nodeId`（父节点）、`nodeAttempt`（节点尝试）、`segmentSequence`（节点分段）、`modelTurn`（分段内模型回合）、`memberSeq`（本轮工具调用原始序号）和 `toolCallId`（模型给出的工具调用号）。创建入口先核对控制行在当前泳道和部署代际可用；子 Run 创建时再用已落库的父等待组和工具成员逐项匹配，命中的子 Run 才记录自己的控制编号。未命中的子 Run 正常执行，父级 `acceptanceFixtureId` 或 `acceptanceControlId` 不会传给子 Run；同一控制编号不能同时绑定两个父工具成员，也不能由父 Run 和子 Run 共用。

这些身份值来自验收前冻结的父计划与指定父模型回合，运行证据必须核对实际父等待组与条目一致。真实父模型若生成了无法预知的节点、回合或调用号，该轮受控样本记为准备失败，不用“第一个子代理”等运行时顺序猜目标；父子模型均真实执行的普通样本不需要控制条目。放行点按被暂缓的**子 Run ID**和策略里的 `holdUntilPoint` 名称写入、放行。乱序样本只给第一个子 Run 对应的父工具成员配置条目，第二个子 Run 不绑定控制行，并从子 Run 上下文与策略命中记录核实实际目标。

模型未形成指定子代理工具调用、两子请求或子计划形状时，这次样本不满足准备条件。保存该轮真实模型输出，按开发计划事先固定的次数重新准备；不把夹具驱动样本记为全真实模型样本。

## 服务端只读证据输入

`--child-evidence-file` 必须来自当次获准的只读采集器，不能手工填写或沿用旧文件。文件顶层至少有 `source: "server_read_only"`、`complete: true`、`rootRunId`、覆盖无界面测试起止时间的 `window.startedAt/endedAt`，以及以下数组：

- `childRuns`：每项记录 `runId`、`parentRunId`、`rootRunId`、`status`、`terminalAt`。
- `creationIntents`：每项记录 `intentId`、`operationId`、`outboxId`、`toolCallId`、`parentNodeId`、`childRunId`、`parentRunId`、`rootRunId`、`status`、`acceptedAt`、`outboxStatus`。受理后的意图在子 Run 收尾时可从 `ACCEPTED` 变为 `TERMINAL`；验收以非空 `acceptedAt` 和 outbox 的 `ACKED` 证明曾受理。
- `waitGroups`：每项记录 `groupId`、`parentRunId`、`toolCallId`、`requestedChildRunIds`、`results`、`startedAt`、`completedAt`、`resumeNotificationId`。`results` 每项有 `childRunId`、`status`；正常样本还需 `resultText`。超时样本第一条等待还需 `deadlineAt`。
- `runEvents`：父和每个子 Run 至少有一条带 `runId`、`eventType` 的服务端事件。脚本将每条 Run 的事件类型写入结果，供人工核对完整时间线。

乱序样本还需 `externalResultControl`：`realSandbox: true`、`heldChildRunId` 和 `releasedAt`；脚本核对放行发生在第二个子 Run 终态之后、第一个子 Run 终态之前。超时样本还需 `parentWaitPermitSamples`，每条含 `parentRunId`、`at`、`nodePermits` 和 `coordinationPermits`；至少一条落在第一次等待时间窗内，证明父等待期间两类许可均为零。事后查询这些业务表无法证明 Sandbox 人为暂缓与放行的控制动作，也无法重建父等待期间进程内两类许可的即时读数；采集器明确列出这两个缺口，不能据最终状态倒填。两场景须另有当时的真实控制日志和同步许可采样，否则判定为证据不足。

现有 v1.3 面向用户的 Run 生命周期接口与 headless 输出只有顶层 Run 身份、事件和结果；没有独立子 Run 的父子关系、创建意图、待投递号、等待组和父等待许可读数。它们须由阶段 5A 新增的只读接口或已获准的服务端采集器提供。字段缺失时脚本返回 `evidence_incomplete`，不会从最终回答或客户端声明推测这些事实。

本补充判定不能替代原执行器的泳道路由、服务端配置指纹、超时取消和退出状态检查。正式 Beta 结论仍需核对原 `evidence_manifest.json` 的路由证据、服务端采集窗口与实际测试窗口、候选提交和镜像配置。
