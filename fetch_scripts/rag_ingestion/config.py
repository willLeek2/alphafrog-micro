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
    # 注意：/rag/records/* 端点现在需要 JWT 登录，admin-token 仅用于 /rag/ingest 和 /rag/upload-doc
    ingest_admin_token: str

    # 登录凭据（用于 /api/auth/login 获取 JWT token）
    # /rag/records/* 端点需要 JWT 鉴权（Spring Security），不再接受纯 admin-token
    login_username: str = ""
    login_password: str = ""

    # ── 以下字段预留给未来多模态 RAG，当前流程不使用 ──────────────
    # PDF OCR provider（"baidu" | "aliyun"）
    pdf_parser_provider: str = ""
    baidu_doc_parser_url: str = ""
    baidu_doc_parser_token: str = ""
    aliyun_doc_parser_endpoint: str = ""
    aliyun_access_key_id: str = ""
    aliyun_access_key_secret: str = ""

    # OpenRouter provider order（可选），例：["openai", "azure"]
    # 当 endpoint 为 OpenRouter 时可显式指定 provider 优先级
    embedding_provider_order: list = field(default_factory=list)


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

        # provider order：逗号分隔列表，如 "openai,azure"
        embedding_provider_order=_parse_provider_order(
            os.environ.get("EMBEDDING_PROVIDER_ORDER", "")
        ),

        ingest_admin_token=os.environ.get("INGEST_ADMIN_TOKEN", ""),

        # 登录凭据（/rag/records/* 需要 JWT 鉴权）
        # 不再从 env 读取；由 YAML 全局 auth 块覆盖
        login_username="",
        login_password="",

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


def _parse_provider_order(raw: str) -> list:
    """把逗号分隔字符串转成 list，空字符串返回 []"""
    if not raw or not raw.strip():
        return []
    return [p.strip() for p in raw.split(",") if p.strip()]


def config_with_embedding_override(cfg: Config, emb: dict) -> Config:
    """
    基于全局 Config 生成一份 embedding 字段被 scenario YAML 覆盖的副本。
    emb 为 scenario['embedding'] dict，可为 None。
    """
    if not emb:
        return cfg

    # dataclass replace 不支持可变默认值，手动构造
    # 用 `or cfg.x` 处理 scenario 显式写空字符串的情况（空字符串不应覆盖全局值）
    return Config(
        service_base_url=cfg.service_base_url,
        jina_api_key=cfg.jina_api_key,
        embedding_base_url=emb.get("base_url") or cfg.embedding_base_url,
        embedding_api_key=emb.get("api_key") or cfg.embedding_api_key,
        embedding_model=emb.get("model") or cfg.embedding_model,
        embedding_dim=int(emb.get("dim") or cfg.embedding_dim),
        ingest_admin_token=cfg.ingest_admin_token,
        login_username=cfg.login_username,
        login_password=cfg.login_password,
        pdf_parser_provider=cfg.pdf_parser_provider,
        baidu_doc_parser_url=cfg.baidu_doc_parser_url,
        baidu_doc_parser_token=cfg.baidu_doc_parser_token,
        aliyun_doc_parser_endpoint=cfg.aliyun_doc_parser_endpoint,
        aliyun_access_key_id=cfg.aliyun_access_key_id,
        aliyun_access_key_secret=cfg.aliyun_access_key_secret,
        embedding_provider_order=(
            emb.get("provider", {}).get("order")
            or cfg.embedding_provider_order
        ),
    )


def config_with_auth_override(cfg: Config, auth: dict) -> Config:
    """
    基于全局 Config 生成一份 auth 字段被 YAML 覆盖的副本。
    auth 为 config['auth'] dict，可为 None。
    """
    if not auth:
        return cfg

    return Config(
        service_base_url=cfg.service_base_url,
        jina_api_key=cfg.jina_api_key,
        embedding_base_url=cfg.embedding_base_url,
        embedding_api_key=cfg.embedding_api_key,
        embedding_model=cfg.embedding_model,
        embedding_dim=cfg.embedding_dim,
        ingest_admin_token=cfg.ingest_admin_token,
        login_username=auth.get("username") or cfg.login_username,
        login_password=auth.get("password") or cfg.login_password,
        pdf_parser_provider=cfg.pdf_parser_provider,
        baidu_doc_parser_url=cfg.baidu_doc_parser_url,
        baidu_doc_parser_token=cfg.baidu_doc_parser_token,
        aliyun_doc_parser_endpoint=cfg.aliyun_doc_parser_endpoint,
        aliyun_access_key_id=cfg.aliyun_access_key_id,
        aliyun_access_key_secret=cfg.aliyun_access_key_secret,
        embedding_provider_order=cfg.embedding_provider_order,
    )
