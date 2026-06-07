import os
from dataclasses import dataclass, field


@dataclass
class Config:
    # alphafrog frontend base URL（公网 8090 / 内部 18096 都用同一组路径）
    # 所有 RAG ingestion 相关端点（/rag/records/*、/rag/ingest、/rag/upload-doc）
    # 都挂在 frontend 下，由 frontend 转发到 externalInfoService。
    service_base_url: str

    # Jina Reader API（爬取网页/PDF → Markdown）
    jina_api_key: str              # https://jina.ai/reader/ 申请，免费 key 500 RPM

    # Embedding API（OpenRouter，OpenAI 兼容格式）
    embedding_base_url: str
    embedding_api_key: str
    embedding_model: str
    embedding_dim: int

    # frontend Bearer token（对应 Java 侧 alphafrog.rag.ingest.admin-token，
    # 通常从环境变量 AF_RAG_INGEST_TOKEN 同步过来）
    ingest_admin_token: str

    # ── 以下字段预留给未来多模态 RAG，当前流程不使用 ──────────────
    # PDF OCR provider（"baidu" | "aliyun"）
    pdf_parser_provider: str = ""
    baidu_doc_parser_url: str = ""
    baidu_doc_parser_token: str = ""
    aliyun_doc_parser_endpoint: str = ""
    aliyun_access_key_id: str = ""
    aliyun_access_key_secret: str = ""


def load_config() -> Config:
    return Config(
        # 缺省指向本地 8090（test_scripts 风格），用户可通过环境变量覆盖
        service_base_url=os.environ.get("ALPHAFROG_BASE_URL", "http://localhost:8090"),

        jina_api_key=os.environ.get("JINA_API_KEY", ""),

        embedding_base_url=os.environ.get("EMBEDDING_BASE_URL",
                                          "https://openrouter.ai/api/v1"),
        embedding_api_key=os.environ["EMBEDDING_API_KEY"],
        embedding_model=os.environ.get("EMBEDDING_MODEL",
                                       "openai/text-embedding-3-small"),
        embedding_dim=int(os.environ.get("EMBEDDING_DIM", "1024")),

        ingest_admin_token=os.environ.get("INGEST_ADMIN_TOKEN", ""),

        # OCR 相关（可选，当前不使用）
        pdf_parser_provider=os.environ.get("PDF_PARSER_PROVIDER", ""),
        baidu_doc_parser_url=os.environ.get("BAIDU_DOC_PARSER_URL", ""),
        baidu_doc_parser_token=os.environ.get("BAIDU_DOC_PARSER_TOKEN", ""),
        aliyun_doc_parser_endpoint=os.environ.get("ALIYUN_DOC_PARSER_ENDPOINT", ""),
        aliyun_access_key_id=os.environ.get(
            "ALIYUN_ACCESS_KEY_ID",
            os.environ.get("OSS_ACCESS_KEY_ID", "")),
        aliyun_access_key_secret=os.environ.get(
            "ALIYUN_ACCESS_KEY_SECRET",
            os.environ.get("OSS_ACCESS_KEY_SECRET", "")),
    )
