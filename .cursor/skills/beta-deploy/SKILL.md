---
name: beta-deploy
description: 开发机 Agent 通过 SSH 包装脚本调用 Beta 机本机接待命令 af-beta，完成泳道/主环境构建与滚动。只负责选对子命令和参数；镜像构建、部署单填写、端口和口令都在 Beta 本机完成。
---

# Beta 部署接待命令调用规范（开发机 Agent）

## 适用场景

当前分支需要在 Beta 环境验证时：开一条泳道（或经用户明确同意后更新主 Beta 环境），通过本仓库的 SSH 包装脚本调用 Beta 机本机接待命令 `af-beta`。

## 硬性边界

- 默认只做泳道（lane）操作。**更新主 Beta 环境（main roll）必须用户在当前对话里明确说了才允许调用**。
- 镜像构建、部署单填写、端口分配、口令读取都发生在 Beta 本机，由 `af-beta` 完成；开发机 Agent 的职责是选对子命令和参数（泳道名、服务短名、git 提交）。
- 对 Beta 的访问只有包装脚本这一条路：专用受限钥匙（Beta 侧 authorized_keys 绑定强制命令 af-beta-shell，服务端只放行 af-beta 白名单子命令，拿不到 shell、不能端口转发）。**不得尝试用其它钥匙、其它用户、直接 ssh、scp、端口转发或任何方式连 Beta**。
- 不读取、不修改 `/etc/alphafrog-beta/` 下的任何文件。
- 不执行 `docker rm`、`systemctl` 等主机操作。
- 不在对话里打印口令文件内容或任何密钥（包括专用私钥）。

## 调用方式

统一通过仓库内的 SSH 包装脚本，不自己拼 curl、不直接 ssh 进去执行别的命令：

```bash
bash deploy/beta/af-beta-remote.sh <af-beta 子命令> [参数]
```

- 主机地址来自开发机环境变量 `AF_BETA_SSH`（每台开发机自己配置，例如 `user@beta-host`），专用私钥路径 `AF_BETA_KEY`（默认 `~/.ssh/af-beta-ed25519`）；仓库和对话里都不出现真实地址和密钥。
- 成功后把 `how-to-test` 子命令的输出**原样**转告用户（那是 Beta 侧生成的验证指引）。
- 失败时优先调用 `retry`；retry 无法解决或报错不在接待命令职责内时，把错误**原文**交给用户，不自行猜测修法。

## 服务短名对照

与 Beta 机文档《03-beta-机开发入口》的对照表保持一致；以那份文档为权威，发现不一致以它为准并在对话里指出：

| 部署单里的服务名 | 说明 |
| --- | --- |
| domestic-stock-service | 行情（Beta 本地） |
| domestic-fetch-service | 抓取（Beta 本地） |
| admin-service | 管理 |
| portfolio-service | 组合 |
| agent-service | agent-langchain |
| python-sandbox-service | Python 沙箱执行 |
| python-sandbox-gateway-service | 沙箱网关 |
| frontend | 前端入口 |

（短名形式以 03 文档的对照表为准；上表先列部署单实名，供核对。）

## 常用子命令（以 03 文档为准）

- `status`：查看当前部署与滚动状态。
- 泳道相关子命令：按 03 文档用泳道名 + 服务短名 + git 提交发起。
- `main roll`：更新主 Beta 环境。见上文硬性边界：必须用户明确要求。
- `retry`：对失败部署重试。
- `how-to-test`：拿到本次部署的验证指引，成功后原样转告用户。

## 联调前置

- Beta 本机接待命令按 03 文档 1-1 就绪后，`bash deploy/beta/af-beta-remote.sh status` 能跑通即联调成功。
