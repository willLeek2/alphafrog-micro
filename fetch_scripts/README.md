# fetch_scripts

本目录存放与数据抓取、 ingestion 相关的脚本，在用户侧机器上运行，通过 HTTP 连接远程服务端端点。

## 目录结构

```
fetch_scripts/
├── README.md              # 本文件
├── rag_ingestion/         # RAG 文档向量化 ingestion 流水线
│   ├── run.py             # 主入口
│   ├── config.py          # 配置加载（环境变量）
│   ├── db_client.py       # 远程记录查询/状态更新客户端
│   ├── ingest_client.py   # 向量写入 Qdrant（HTTP）
│   ├── oss_uploader.py    # 文档上传 OSS（HTTP）
│   ├── jina_reader.py     # 网页/PDF → Markdown（调 Jina Reader API）
│   ├── embedder.py        # 文本 embedding（调 OpenAI 兼容 API）
│   ├── chunker.py         # 字符级滑动窗口切块
│   ├── pdf_parser.py      # PDF 解析（预留，当前未启用）
│   ├── ts_code_filter.py  # ts_code 过滤抽象（list / select）
│   ├── date_utils.py      # 日期工具函数
│   ├── requirements.txt   # Python 依赖
│   └── configs/           # YAML 任务配置样例
└── from_test_scripts/     # 【预留】未来可能从 test_scripts/ 移入的脚本
    └── README.md
```

## 工作模式

对齐 `test_scripts/` 的工作模式：
- 脚本在用户侧机器上运行，不依赖远程环境的本地资源（如数据库直连）
- 所有需要服务端资源的操作均通过 HTTP API 调用
- 配置通过环境变量或 YAML 文件指定远程端点

## 远程端点依赖

当前 `rag_ingestion/` 依赖以下远程 HTTP 端点：
- `POST {base}/rag/upload-doc` — 文档上传 OSS
- `POST {base}/rag/ingest` — 向量写入 Qdrant
- `POST {base}/rag/records/list-unprocessed` — 查询待处理记录
- `POST {base}/rag/records/mark-oss-uploaded` — 标记已上传
- `POST {base}/rag/records/mark-vectorized` — 标记已向量化

鉴权方式：`Authorization: Bearer <AF_RAG_INGEST_TOKEN>`
