# executePython 常见陷阱与模板

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

## 路径与文件名

| 错误 | 正确 |
|------|------|
| `.../data.csv` | `.../<dataset_id>.csv` |
| 硬编码单目录 | `glob.glob("/sandbox/input/*")` |

## 收益率计算（ETF）

若 `getExchangeAssetDaily(assetType=etf)` 返回含 `adj_factor` 列，回测优先用 `close * adj_factor`。

## 执行策略

- **简单任务**：一次 `executePython` 完成加载 + 计算 + 输出。
- **复杂多阶段任务**：可分次调用，每次输出明确中间结果。

## 失败排查顺序

1. `dataset_ids` 是否来自当前 run 的上游工具返回
2. manifest 是否存在 failed members
3. `from af_dataset_loader import load_manifest` 是否 ImportError（若失败说明沙箱 loader 未挂载，改用手动 CSV 并上报）
4. 阅读 stderr 中的 Python traceback，不要重复提交相同错误代码
