# Python 沙箱执行指南（executePython）

## 环境说明

- 每次 `executePython` 在**全新隔离容器**中运行；变量与中间状态不会保留到下一次调用。
- 不要直接构造 `/sandbox/input/...` 三层存储路径，也不要传入原始 `dataset_id` / `manifest_id` / scope hash / runId。
- executePython 启动后，沙箱会自动注入两个 CSV 索引（绝对路径）：
  - `/sandbox/paths_dataset.csv`：`agent_run_dataset_id, dataset_file_path, from_ts_code`
  - `/sandbox/path_manifest.csv`：`agent_run_manifest_id, manifest_file_path, related_dataset_ids`

## run-level 编号规则

- `dataset_ids` 与 `manifest_ids` 都是当前 agent run 的 **run-level 整数编号**，不是原始 ID。
- dataset 编号空间与 manifest 编号空间相互独立，均从 1 开始。
- 单数据集：`dataset_ids: "1"`；多数据集：`dataset_ids: "1,3"`。
- 数据已打包成 manifest 时，优先使用 `manifest_ids`，例如 `manifest_ids: "1"` 或 `"1,2"`。
- 不确定编号时，先调用 `listMyData(query_type="dataset")` 或 `listMyData(query_type="manifest")` 查询。
- executePython 报错 `ILLEGAL_RUN_LEVEL_IDS` 时，错误详情会给出 `legal_dataset_numbers` / `legal_manifest_numbers`，从这两个列表取值重试。

## af_dataset_loader（已预置）

沙箱执行环境已复制 `af_dataset_loader.py` 到 `/sandbox/`，可直接：

```python
from af_dataset_loader import load_manifest, load_datasets

# 按 run-level manifest 编号读取
result = load_manifest("1")
df = result.frame
print(result.failed_members, result.skipped_members)

# 按 run-level dataset 编号读取
dfs = load_datasets("1")
for ts_code, df in dfs.items():
    print(ts_code, df.shape)
```

`load_datasets("1")` 返回 `dict[from_ts_code, DataFrame]`；`from_ts_code` 可能为 `UNCERTAIN`，仅表示系统无法从工具入参简单判定资产代码，不代表数据损坏。

## 手动读取 CSV 索引

```python
import pandas as pd

# dataset 索引
ds_idx = pd.read_csv("/sandbox/paths_dataset.csv")
path = ds_idx[ds_idx["agent_run_dataset_id"] == 1].iloc[0]["dataset_file_path"]
df = pd.read_csv(path)

# manifest 索引
mf_idx = pd.read_csv("/sandbox/path_manifest.csv")
manifest_path = mf_idx[mf_idx["agent_run_manifest_id"] == 1].iloc[0]["manifest_file_path"]
```

## 参数要求

- `code` 必填。
- `dataset_ids` 与 `manifest_ids` 至少传一个。
- `libraries` 可选，逗号分隔，例如 `"numpy,pandas"`；优先使用预装库。
- `timeout_seconds` 可选，默认 30。

## 预装库

numpy、pandas、matplotlib、scipy 已预装；优先使用预装库，减少安装延迟。

## 错误恢复

- `MISSING_IDS`：dataset_ids 与 manifest_ids 都为空 → 至少传一个。
- `ILLEGAL_RUN_LEVEL_IDS`：编号不在当前 run 合法集合 → 用错误详情中的 legal lists 重试，或先调用 listMyData。
- 连续 2 次失败均与编号/路径有关时，停止重试，改为调用 listMyData 或换用其他工具策略。
