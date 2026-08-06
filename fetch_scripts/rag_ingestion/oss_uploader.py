"""
将文档内容 POST 给 frontend 的 /rag/upload-doc 端点，
由 frontend 转发到 externalInfoService 经 VPC 内网上传到阿里云 OSS，返回 object key。
AK/SK 仅保存在服务端，本地脚本不再持有 OSS 凭证。
"""
import requests

from config import Config
from auth_client import get_admin_jwt


def upload_doc(
    cfg: Config,
    doc_type: str,          # "ann" | "research"
    ts_code: str,
    date: str,               # YYYYMMDD
    title: str,
    content: str,            # Markdown 或纯文本内容
    file_extension: str = ".md",
) -> str:
    """
    将文档内容发送给 frontend 的 /rag/upload-doc 端点，
    由 frontend 转发到服务端，服务端经 VPC 内网上传到 OSS。
    返回 object key，例如 "alphafrog-rag/ann/000001.SZ/20260315_标题.md"。
    """
    payload = {
        "docType": doc_type,
        "tsCode": ts_code,
        "date": date,
        "title": title,
        "content": content,
        "fileExtension": file_extension,
    }
    headers = {"Authorization": f"Bearer {get_admin_jwt(cfg)}"}

    url = cfg.service_base_url.rstrip("/") + "/rag/upload-doc"
    resp = requests.post(
        url,
        json=payload,
        headers=headers,
        timeout=60.0,
    )
    resp.raise_for_status()
    return resp.json()["ossKey"]
