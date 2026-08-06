# Dataset Manifest 使用指南

## 何时使用 manifest

当 batch 日线/搜索工具返回一组数据时，系统可能把多个原子 dataset 聚合为一个 manifest，并在当前 agent run 内分配 **run-level manifest 编号**。

调用 `executePython` 时，manifest 必须通过 `manifest_ids` 传 run-level 整数编号，例如：

```json
{"manifest_ids":"1","dataset_ids":"","libraries":"pandas","timeout_seconds":30}
```

不要把原始 `manifestId` / `dataset_id` / 文件路径传给 `dataset_ids` 或 `manifest_ids`。

## manifest.json 关键字段

| 字段 | 说明 |
|------|------|
| `manifestId` | 原始 manifest 标识，仅用于诊断，不用于 executePython 入参 |
| `kind` | manifest 类型 |
| `dataType` | 如 `stock_daily`、`index_daily` |
| `startDate` / `endDate` | `YYYYMMDD` |
| `members[]` | 成员列表 |
| `members[].tsCode` | 标的代码 |
| `members[].datasetId` | 成员 dataset 标识或 run-level member 编号，代码里不要直接拼路径 |
| `members[].status` | `ready` / `failed` / `skipped` / `broken` |

仅 `status=ready` 的成员会被加载；failed/skipped/broken 成员不会进入主 DataFrame，但可通过 loader 结果查看。

## 推荐 API

```python
from af_dataset_loader import load_manifest

# 按 run-level manifest 编号合并为单表，含 ts_code 列
result = load_manifest("1")
df = result.frame

print(result.failed_members)
print(result.skipped_members)
```

多个 manifest 时，`executePython` 工具参数可传 `manifest_ids: "1,2"`；Python 代码里推荐逐个读取后合并：

```python
from af_dataset_loader import load_manifest
import pandas as pd

results = [load_manifest(mid) for mid in ["1", "2"]]
frames = [item.frame for item in results if not item.frame.empty]
df = pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()
failed_members = [member for item in results for member in item.failed_members]
skipped_members = [member for item in results for member in item.skipped_members]
```

## Partial failure

Batch 写入可能产生 `ready_count < member_count` 的 manifest。务必检查：

- `result.failed_members`
- `result.skipped_members`

如果 `result.frame` 为空，或某些成员缺失，不要假设字段一定存在；先判断 `df.empty` 与 `df.columns`，再做计算。在最终回答中说明缺失成员，不要把 partial 结果当作完整 universe。

## 路径规则

不要直接构造旧输入目录模板、旧 task 运行目录或任何历史 task 的路径。每次 `executePython` 都是新的独立沙盒，真实路径只应来自：

- `load_manifest("<run-level manifest number>")`
- `/sandbox/path_manifest.csv` 的 `manifest_file_path`
- `/sandbox/paths_dataset.csv` 的 `dataset_file_path`
