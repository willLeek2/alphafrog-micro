# Python 沙箱服务（FastAPI + llm-sandbox）

## 作用
为 agent 提供 Python 代码执行能力，支持通过 `dataset_id` 将 Java 侧落盘的数据复制进容器执行环境。

## 启动
```bash
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8095
```

## 环境变量
- `AF_SANDBOX_DATA_DIR`：数据集基目录（默认 `data/agent_datasets`）
- `AF_SANDBOX_MAX_CONCURRENCY`：最大并行数（默认 `2`）
- `AF_SANDBOX_EXECUTION_TIMEOUT`：执行超时秒数（默认 `5`）
- `AF_SANDBOX_MEMORY`：容器内存限制（默认 `512m`）
- `AF_SANDBOX_MEMSWAP`：内存+swap 限制（默认 `512m`）
- `AF_SANDBOX_BACKEND`：容器后端（默认 `docker`）
- `AF_SANDBOX_WORKDIR`：容器工作目录（默认 `/sandbox`）
- `AF_SANDBOX_SKIP_ENVIRONMENT_SETUP`：是否跳过 llm-sandbox 每次创建 venv/升级 pip 的环境初始化。当前非 root 容器模式**只支持 `true`（默认）**：`false` 会启动失败关闭——该路径依赖 llm-sandbox 以 root 执行 chown，且运行时镜像的 venv 是构建期 root 属主，与非 root 容器合同不相容（260818）。
- `AF_SANDBOX_CHILD_USER`：沙箱容器以哪个用户运行（docker `--user` 语义，默认 `alphafrog-sandbox` = uid 10000/gid 10001，等价写法 `10000:10001`）。容器内不存在任何 root 进程；启动后服务会实际校验容器内 uid/gid 非零、数字形式与配置精确一致，任一失败即关闭容器并拒绝服务。root 的任何写法（`root`、`0`、`root:10001` 等）启动即拒。
- `AF_SANDBOX_PREINSTALLED_LIBRARIES`：运行时镜像已预装、无需每次 pip install 的库（默认 `numpy,pandas,matplotlib,scipy`）
- `AF_SANDBOX_ALLOW_CREATE_WITHOUT_OPERATION_ID`：是否允许无 `operation_id` 的旧创建路径（默认 `false`，生产失败关闭）

### D14 非生产兼容开关（必须成组启用）

生产 create 默认要求非空 `operation_id` 与完整 canonical 身份字段，并写入 operation 索引。

若开发/单测需要无键 Legacy 夹具，必须**同时**显式打开三层开关（只开一层会在下一层失败）：

| 层 | 开关 | 默认 |
|---|---|---|
| Java Tools | `sandbox.create.allow-legacy-without-capacity` / `AF_SANDBOX_ALLOW_LEGACY_WITHOUT_CAPACITY` | `false` |
| Gateway | `sandbox.gateway.allow-create-without-operation-id` / `AF_SANDBOX_GATEWAY_ALLOW_CREATE_WITHOUT_OPERATION_ID` | `false` |
| Python | `AF_SANDBOX_ALLOW_CREATE_WITHOUT_OPERATION_ID` | `false` |

约束（违反即不可接生产）：

- 无全局容量准入
- 无 `operationId` 幂等恢复
- 不可把启用了上述开关的进程指向生产 Gateway/Python

## 数据约定
Java 侧建议将数据落盘：
```
<data_dir>/<dataset_id>/data.parquet
<data_dir>/<dataset_id>/meta.json
```

## 接口
### POST /execute
请求体：
```json
{
  "dataset_id": "ds_20240101_abc",
  "code": "print('hello')",
  "files": ["data.parquet", "meta.json"],
  "libraries": ["numpy"],
  "timeout_seconds": 5
}
```

返回：
```json
{
  "ok": true,
  "exit_code": 0,
  "stdout": "hello\n",
  "stderr": "",
  "dataset_dir": "/sandbox/input/ds_20240101_abc"
}
```

## 说明
- 非根拷贝通道（`container_copy` → docker `put_archive`，tar 条目属主=容器用户）：请求时将数据文件复制到容器内的 `dataset_dir`；llm-sandbox 的 `copy_to_runtime`（内部以 root 执行 chown）已从所有生产路径移除。
- 默认会安装 numpy（可通过 `libraries` 覆盖）。

## 输出上限与 Nacos 配置快照（MethodSpec V5 §7.2）

`python-sandbox.json`（Nacos data id）使用四个 camelCase 键：`stdoutMaxBytes`、
`stderrMaxBytes`、`recordChannelMaxBytes`、`recordChannelMaxRecords`
（示例见 `config/python-sandbox.example.json`）。

- **应用默认值**：`app/config.py` 的 `DEFAULT_OUTPUT_LIMITS`，取自 Spec §7.1
  包装器输入示例的形状值；正式生产数字必须由工作包 C/D 的四段测试确认后更新。
- **静态硬上限**：`app/config.py` 的 `HARD_OUTPUT_LIMIT_CEILINGS`，写在代码里。
  Nacos 只能把值调低或调到硬上限，超过硬上限的值会被钳制到硬上限并记录事件，
  动态配置永远不能提高硬上限。
- **整份校验 + last-known-good**：`DynamicSandboxConfig` 保存一份完整、不可变
  的上一已知可用配置快照。整份 payload 校验通过（JSON 对象、每个已知键类型/
  范围合法）才原子替换；任何非法 payload（坏 JSON、非对象、类型错误、负值）
  整体拒绝并继续使用上一已知可用配置，不做部分应用。未知键忽略并告警。
- **任务快照冻结**：`output_limits_snapshot()` 返回四个上限加 `sourceRevision`
  共五个键；`create_task()` 创建任务时冻结该快照（幂等重复创建返回原快照），
  执行中只读任务快照，不得重新读取热配置。
- **Nacos 默认关闭**：`AF_CONFIG_NACOS_ENABLED` 缺省不为 `true` 即不启动监听；
  生产发布时显式打开。四段测试未完成前，金融计算结果通道保持关闭。
