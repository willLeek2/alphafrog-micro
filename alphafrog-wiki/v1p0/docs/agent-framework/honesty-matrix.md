# 工程诚实度清单

本页记录代码或文档曾经声明、但生产实现尚未提供的能力。清单只描述真实边界；行为修复仍由对应交付负责。

| 声称或旧文案 | 当前真实边界 | 处置 | 交付 |
|---|---|---|---|
| `LangchainSingleWriterGuard` 保护同一 Run 的读写归属 | 已删除空守卫。当前只校验 Run 存在及用户归属，没有跨实例 Run 单写者租约；ToolJob anchor/lease/CAS 不等同于整个 Run 的写者归属 | 删除伪述；若未来需要实例级租约，必须另建持久化 owner/lease/takeover 协议 | D23 已收口 |
| health 返回“编排未实现”或 `P0-skeleton` | `orchestrationStatus` 固定为 `LINEAR_PIPELINE_READY`、`PROVIDER_DISABLED`、`LINEAR_PIPELINE_UNAVAILABLE`；版本缺失时为 `UNKNOWN` | 删除自由文本与历史阶段名 | D23 已收口 |
| 单阶段规划具备与两阶段相同的结构保障 | 单阶段为显式 legacy fallback，但复用相同 raw JSON parser、Todo validator 和 `maxAttempts` | 测试默认两阶段；legacy 用例显式命名 | D09 已收口 |
| Todo planning 的 `toolWhitelist` 参数执行工具白名单校验 | Todo schema 不含工具字段；该参数已删除。规划 prompt 只提示可用工具，最终授权由执行期 ToolRouter 负责 | 删除名存实亡的签名与文案 | D09 已收口 |
| 空 Todo 输出可自动恢复 | 仍由 D10 定义 attempt-local evidence 与恢复/失败分类，本页不预判实现完成 | 保留问题跟踪，不冒充已修复 | D10 待交付 |
| 工作区指纹相同即可安全跳过 dump | D21 负责真实工作区指纹、幂等与冲突语义；D23 不实现 dump 行为 | 保留问题跟踪，不冒充已修复 | D21 待交付 |
| 生产热点类中的面试/教学长注释属于运行契约 | 工程约束应留在源码，教学叙述迁出。W0 只清理独占文件，共享文件按 single-writer 归属顺序处理 | D05/D07 相关文件归 W1；观测文件归 W4；Run 控制/读取冲突面归 W5/W0 集成时复核 | D23 跟踪至各 owner |
| `agentic-poc` / `subagent-poc` 是生产能力 | 两目录仅在显式 Maven profile 下加入 source roots，默认构建和生产 Spring/Dubbo 均不可见 | 保留实验资产并在目录 README 写明边界 | D23 已收口 |

## Health 告警口径

顶层 `status=UP` 只表示进程 liveness，不表示 provider readiness。建议告警条件为：

```text
providerEnabled == true && orchestrationStatus != "LINEAR_PIPELINE_READY"
```

仓库内未发现旧 `orchestrationStatus` 自由文本的消费者；外部消费者若存在，需按以上稳定枚举迁移。

## 规划配置口径

规划结构校验的唯一重试键为：

```text
agent.llm.runtime.planning.structured-output.max-attempts
```

热加载配置优先于 Spring/Nacos 静态配置；缺失、非正数或大于 10 时回落默认值 2。
