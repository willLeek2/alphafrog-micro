# executePython 常见陷阱与模板

## run-level 编号（最重要）

- `dataset_ids` / `manifest_ids` 必须是当前 agent run 的 **run-level 整数编号**，不是原始 `dataset_id` / `manifest_id` 字符串，也不是文件路径或 scope hash。
- dataset 编号空间与 manifest 编号空间相互独立，均从 1 开始。
- 单数据集：`dataset_ids: "1"`；多数据集：`dataset_ids: "1,3"`。
- 数据已打包成 manifest 时，优先使用 `manifest_ids`，例如 `manifest_ids: "1"`。
- 不确定编号时，先调用 `listMyData(query_type="dataset")` 或 `listMyData(query_type="manifest")`。
- executePython 报错 `ILLEGAL_RUN_LEVEL_IDS` 时，用错误详情里的 `legal_dataset_numbers` / `legal_manifest_numbers` 重试。

## 数据读取方式

**推荐**：使用沙箱预置 helper。

```python
from af_dataset_loader import load_datasets, load_manifest

# run-level dataset 编号
result = load_datasets("1")
if result:
    df = list(result.values())[0]

# run-level manifest 编号
result = load_manifest("1")
df = result.frame
print(result.failed_members, result.skipped_members)
```

**fallback**：读取 CSV 索引。

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

`from_ts_code` 可能为 `UNCERTAIN`，仅表示系统无法从工具入参简单判定资产代码，不代表数据损坏。

## 路径与文件名（旧契约已废弃）

| 旧写法（不要再用） | 新写法 |
|------|------|
| `/sandbox/input/{dataset_id}/{dataset_id}.csv` | `load_datasets("1")` 或读 `/sandbox/paths_dataset.csv` |
| `glob.glob('/sandbox/input/*')` | `load_manifest("1")` 或读 `/sandbox/path_manifest.csv` |
| `data.csv` | 通过 CSV 索引拿到真实 `dataset_file_path` 后再 `pd.read_csv(path)` |

## trade_date 解析（极易出错）

`trade_date` 可能是 **8 位字符串**（`20250101`）或 **13 位毫秒时间戳**（`1735660800000`）。

```python
sample = str(df["trade_date"].iloc[0])
if len(sample) == 13:
    df["date"] = pd.to_datetime(df["trade_date"], unit="ms")
elif len(sample) == 8:
    df["date"] = pd.to_datetime(df["trade_date"], format="%Y%m%d")
else:
    df["date"] = pd.to_datetime(df["trade_date"])
df = df.sort_values("date")
```

**禁止**对大整数使用无 `unit` 的 `pd.to_datetime()`——会被当成纳秒，日期会错到 1970 年。

## 收益率计算（ETF）

若 `getExchangeAssetDaily(assetType=etf)` 返回含 `adj_factor` 列，回测优先用 `close * adj_factor`。

## 执行策略

- **简单任务**：一次 `executePython` 完成加载 + 计算 + 输出。
- **复杂多阶段任务**：可分次调用，每次输出明确中间结果。

## 失败排查顺序

1. `dataset_ids` / `manifest_ids` 是否是 run-level 整数编号（不是原始 dataset_id）。
2. 编号不确定时先调用 `listMyData` 确认。
3. manifest 是否存在 failed members；`load_manifest` 结果要检查 `failed_members` / `skipped_members`。
4. `from af_dataset_loader import load_manifest` 是否 ImportError（若失败说明沙箱 loader 未挂载，改用手动 CSV 索引并上报）。
5. 阅读 stderr 中的 Python traceback，不要重复提交相同错误代码。
6. 连续 2 次同类编号/路径失败后，停止重试，换用其他工具策略。
