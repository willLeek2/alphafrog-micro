# Dataset Manifest 使用指南

## 何时使用 manifest

当 batch 日线/搜索工具返回 `dataset_id` 且类型为 **dataset_manifest** 时，表示多个原子 dataset 被聚合为一个逻辑视图。Manifest 元数据位于：

```
/sandbox/input/<manifest_id>/<manifest_id>.manifest.json
```

## manifest.json 关键字段

| 字段 | 说明 |
|------|------|
| `manifestId` | 与目录名一致 |
| `kind` | 固定 `dataset_manifest` |
| `dataType` | 如 `stock_daily`、`index_daily` |
| `startDate` / `endDate` | `YYYYMMDD` |
| `members[]` | 成员列表 |
| `members[].tsCode` | 标的代码 |
| `members[].datasetId` | 原子 dataset id |
| `members[].status` | `ready` / `failed` / `skipped` |

仅 `status=ready` 的成员会被加载；failed/skipped 成员不会进入 DataFrame，但可通过 loader 结果查看。

## 推荐 API

```python
from af_dataset_loader import load_manifest, load_datasets

# 合并为单表（含 ts_code 列）
result = load_manifest("<manifest_id>")
df = result.frame

# 按 ts_code 分表
by_code = load_datasets("<manifest_id>")
```

## Partial failure

Batch 写入可能产生 **ready_count < member_count** 的 manifest。务必检查：

- `result.failed_members`
- `result.skipped_members`

在最终回答中说明缺失成员，不要把 partial 结果当作完整 universe。

## 与 executePython 配合

`executePython` 的 `dataset_ids` 可只传 manifest id；沙箱服务会展开 ready 原子目录并复制 manifest 文件到任务 workspace。
