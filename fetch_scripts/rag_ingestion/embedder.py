"""
调 OpenAI 兼容 embedding API（通过 OpenRouter 或其他 provider）。
"""
from typing import Dict, List, Optional

import httpx

from config import Config


def get_embeddings(
    texts: List[str], cfg: Config
) -> List[List[float]]:
    """
    批量获取 embedding 向量。
    使用 OpenAI 兼容格式：POST /embeddings。

    支持 provider order（OpenRouter 特有）：
      - cfg.embedding_provider_order 不为空时，在 payload 中附加
        { "provider": { "order": [...] } }
    """
    if not texts:
        return []

    url = f"{cfg.embedding_base_url.rstrip('/')}/embeddings"
    headers = {
        "Authorization": f"Bearer {cfg.embedding_api_key}",
        "Content-Type": "application/json",
    }
    payload: Dict = {
        "model": cfg.embedding_model,
        "input": texts,
    }
    if cfg.embedding_dim:
        payload["dimensions"] = cfg.embedding_dim

    # OpenRouter provider order (仅对 OpenRouter endpoint 发送)
    is_openrouter = "openrouter.ai" in cfg.embedding_base_url
    if is_openrouter and cfg.embedding_provider_order:
        payload["provider"] = {"order": cfg.embedding_provider_order}

    resp = httpx.post(url, json=payload, headers=headers, timeout=60.0)
    resp.raise_for_status()
    data = resp.json()["data"]
    # 按 index 排序后返回
    data.sort(key=lambda x: x["index"])
    return [item["embedding"] for item in data]
