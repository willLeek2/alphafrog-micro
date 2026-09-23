放行点记录 kind：`release-point`

Beta 宿主 `/opt/alphafrog-beta` 当前已登记的记录 kind 只有 `acceptance-fixture`。`git deploy/beta` 里没有 `release-point` 的登记。在本机用接待命令探测的结果是：

```text
records put --kind release-point → 未知记录 kind: release-point（可选: acceptance-fixture）
```

本文件约定 Agent 侧的读法，以及控制面 put 要带哪些字段。宿主 Python 的 kind 安装不在这份 git 里做。

## 表

迁移 `015_agent_run_release_point.sql` 建表 `alphafrog_agent_run_release_point`。

身份约束是 `UNIQUE (run_id, release_key)`：同一个 Run 的一个放行点只能有一行。`opened_at` 与 `opened_by` 成对：两个都空表示还没打开，两个都有值表示已经打开。

`lane_id`、`deployment_version` 两列随行保存，作为「这个点是哪个泳道哪个代际下开的」的证据。它们不参与唯一约束，表本身不按泳道唯一。判断打开与否按 `run_id + release_key` 命中。

## Agent 侧

Agent 的 mapper 只有 `countOpened(runId, releaseKey)`。`AcceptanceReleasePointStore.isOpened` 读这一条语句：行不存在或 `opened_at` 为空都按未打开处理。Agent 进程不插入、不更新这张表。

被 `holdUntilPoint` 压住的等待成员，结果接收方每一轮重新读 `isOpened`。点被打开之后，压住结束，成员按原来的终态路径收尾。压住时限读策略里的 `maxHoldSeconds`。宿主记录的 TTL 管的是这条记录还在不在库里。

## Put 契约

控制面写入一行时，JSON 必带：

- `run_id`：这条 Run 的编号
- `release_key`：策略里 `holdUntilPoint` 的值
- `opened_by`：谁打开了这个点

打开就是把门闩插上：`opened_at` 一旦有值，Agent 侧一直按已打开处理。

宿主 `records put` 现在的 kind 都带 TTL。`acceptance-fixture` 用 60 到 7200 秒。`release-point` 如果沿用同一套宿主记录 TTL，这个 TTL 管的是宿主记录自己还在不在库里。Agent 压住成员的时限读 `maxHoldSeconds`。TTL 到期后宿主记录从控制面消失。表上的打开状态仍以 `opened_at` 是否有值为准。

`lane` 参数与 `acceptance-fixture` 一样，由接待命令按当前泳道填 `lane_id` 和 `deployment_version`。这是宿主记录的泳道范围。表上的唯一键仍是 `run_id + release_key`。

## Get / list

按 `run_id` 加 `release_key` 取一行。列出某个 Run 下全部放行点时，过滤条件同样是 `run_id`，需要精确命中某一个点时再加 `release_key`。

## 与夹具 kind 的分工

`acceptance-fixture` 写夹具表，给整条 Run 换模型脚本，也可以带 `dispatchPolicy`。`release-point` 只写放行点表，给已经压住的成员开门。两条记录各写各的表。
