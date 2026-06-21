# Python 沙箱执行指南（executePython）

## 环境说明

- 每次 `executePython` 在**全新隔离容器**中运行；变量与中间状态不会保留到下一次调用。
- 数据集挂载根目录：`/sandbox/input/<dataset_id>/`
- 标准文件命名：
  - CSV：`/sandbox/input/<dataset_id>/<dataset_id>.csv`
  - 元数据：`/sandbox/input/<dataset_id>/<dataset_id>.meta.json`
  - Manifest：`/sandbox/input/<manifest_id>/<manifest_id>.manifest.json`

## af_dataset_loader（已预置）

沙箱执行环境已复制 `af_dataset_loader.py` / `dataset_manifest.py` 到 `/sandbox/`，可直接：

```python
from af_dataset_loader import load_manifest, load_datasets

result = load_manifest("<manifest_id>")
df = result.frame
print(result.failed_members, result.skipped_members)
```

`load_datasets(manifest_id)` 返回 `dict[ts_code, DataFrame]`，适合按标的分别处理。

## 手动读取（无 manifest 时）

```python
import glob
import os
import pandas as pd

for dataset_path in glob.glob("/sandbox/input/*"):
    dataset_id = os.path.basename(dataset_path)
    df = pd.read_csv(f"{dataset_path}/{dataset_id}.csv")
```

**常见错误**：不要使用 `data.csv` 或 `info.json` 作为默认文件名。

## 参数要求

- `dataset_ids` 必填，来自上游工具返回的 `dataset_id` / `manifest_id`。
- 多数据集用逗号分隔：`"ds_a,ds_b,manifest-xxx"`。
- batch 工具若返回 `manifest_id`，优先传 manifest id；沙箱会自动展开 ready 成员。

## 预装库

numpy、pandas、matplotlib、scipy 已预装；优先使用预装库，减少安装延迟。
