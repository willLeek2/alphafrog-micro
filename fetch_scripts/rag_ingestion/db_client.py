"""
RAG 元数据 HTTP 客户端：通过 frontend 暴露的 /rag/records/* 端点拉记录 / 写状态。

旧实现 (psycopg2 直连) 已废弃——本脚本不再持有任何数据库 DSN，
所有读写经 Bearer 鉴权转发到 alphafrog frontend (公网 8090 / 内部 18096)。

对应服务端:
- frontend 转发层: frontend/.../controller/rag/RagRecordController.java
- 内部实现: externalInfoService/.../ingestion/db/RagRecordController.java + RagRecordService.java
"""
import logging
from typing import Any, Dict, List, Optional

import requests

from config import Config
from ts_code_filter import TsCodeFilter

log = logging.getLogger(__name__)

# ── 文档类型映射 ──────────────────────────────────────────────
# 脚本内部 doc_type 沿用历史命名（"ann" / "research"），与服务端
# alphafrog_rag_announcement / alphafrog_rag_research_report 表名保持一致；
# HTTP 端点上送时用 "announcement" / "research_report"。
_INTERNAL_DOC_TYPE = {
    "ann": "announcement",
    "research": "research_report",
    # 兼容直接传全名（已被某测试 fixture 使用过）
    "announcement": "announcement",
    "research_report": "research_report",
}


class DbClient:
    """RAG 元数据 HTTP 客户端。

    不再持有 db_dsn。所有方法走 HTTP，requests.Session 复用连接。
    """

    def __init__(self, cfg: Config):
        self.base_url = cfg.service_base_url.rstrip("/")
        self.token = cfg.ingest_admin_token
        self.session = requests.Session()
        # 默认超时：拉记录 60s，写状态 30s（写状态 1 行很快）
        self._read_timeout = 60.0
        self._write_timeout = 30.0

    # ── 内部 HTTP helper ──────────────────────────────────────

    def _headers(self) -> Dict[str, str]:
        h = {"Content-Type": "application/json"}
        if self.token:
            h["Authorization"] = f"Bearer {self.token}"
        return h

    def _post(self, path: str, payload: Dict[str, Any], timeout: float) -> Dict[str, Any]:
        url = f"{self.base_url}{path}"
        resp = self.session.post(
            url, json=payload, headers=self._headers(), timeout=timeout
        )
        if resp.status_code >= 400:
            # 暴露错误文本，便于调用方排查；不吞错
            try:
                detail = resp.json()
            except Exception:
                detail = resp.text
            raise RuntimeError(
                f"POST {url} failed: HTTP {resp.status_code} body={detail}"
            )
        return resp.json()

    def _normalize_doc_type(self, doc_type: str) -> str:
        if doc_type not in _INTERNAL_DOC_TYPE:
            raise ValueError(
                f"doc_type 必须是 ann/research/announcement/research_report，收到 {doc_type!r}"
            )
        return _INTERNAL_DOC_TYPE[doc_type]

    # ── 公告 ──────────────────────────────────────────────────

    def get_unprocessed_announcements(
        self,
        limit: int = 50,
        offset: int = 0,
        date_from: str = None,
        date_to: str = None,
        ts_code=None,
        title_patterns: list = None,
    ) -> List[Dict[str, Any]]:
        """查询 vectorized=FALSE 且 oss_url IS NULL 的公告记录。

        可选过滤：ann_date 起止（YYYYMMDD 字符串）、ts_code (str / list / TsCodeFilter)、
                  title_patterns 模糊匹配（OR 关系）。
        """
        records = self._post_unprocessed(
            doc_type="ann",
            limit=limit, offset=offset,
            date_from=date_from, date_to=date_to,
            ts_code=ts_code, title_patterns=title_patterns,
        )
        return self._renormalize_date_field(records, "ann_date")

    def update_announcement_oss_url(self, record_id: int, oss_url: str) -> None:
        """更新公告记录的 oss_url。"""
        self._post_mark("ann", "mark-oss-uploaded", record_id, oss_key=oss_url)

    def mark_announcement_vectorized(self, record_id: int) -> None:
        """标记公告记录 vectorized = TRUE。"""
        self._post_mark("ann", "mark-vectorized", record_id)

    # ── 研报 ──────────────────────────────────────────────────

    def get_unprocessed_reports(
        self,
        limit: int = 50,
        offset: int = 0,
        date_from: str = None,
        date_to: str = None,
        ts_code=None,
        title_patterns: list = None,
    ) -> List[Dict[str, Any]]:
        """查询 vectorized=FALSE 且 oss_url IS NULL 的研报记录。"""
        records = self._post_unprocessed(
            doc_type="research",
            limit=limit, offset=offset,
            date_from=date_from, date_to=date_to,
            ts_code=ts_code, title_patterns=title_patterns,
        )
        return self._renormalize_date_field(records, "trade_date")

    def update_report_oss_url(self, record_id: int, oss_url: str) -> None:
        """更新研报记录的 oss_url。"""
        self._post_mark("research", "mark-oss-uploaded", record_id, oss_key=oss_url)

    def mark_report_vectorized(self, record_id: int) -> None:
        """标记研报记录 vectorized = TRUE。"""
        self._post_mark("research", "mark-vectorized", record_id)

    # ── 内部 ──────────────────────────────────────────────────

    def _post_unprocessed(
        self,
        *,
        doc_type: str,
        limit: int,
        offset: int,
        date_from: Optional[str],
        date_to: Optional[str],
        ts_code,
        title_patterns: Optional[List[str]],
    ) -> List[Dict[str, Any]]:
        api_doc_type = self._normalize_doc_type(doc_type)
        payload: Dict[str, Any] = {
            "docType": api_doc_type,
            "limit": int(limit),
            "offset": int(offset),
        }
        if date_from:
            payload["dateFrom"] = date_from
        if date_to:
            payload["dateTo"] = date_to
        if ts_code is not None:
            # 服务端会再次走 TsCodeFilter 等价校验；这里只规范化 dict/list 形态
            payload["tsCode"] = self._normalize_ts_code_for_http(ts_code, scenario_name=doc_type)
        if title_patterns:
            payload["titlePatterns"] = list(title_patterns)

        body = self._post("/rag/records/list-unprocessed", payload, timeout=self._read_timeout)
        records = body.get("records", [])
        if not isinstance(records, list):
            raise RuntimeError(
                f"list-unprocessed 响应 records 字段不是 list: {type(records).__name__}"
            )
        return records

    def _post_mark(
        self,
        doc_type: str,
        action: str,
        record_id: int,
        *,
        oss_key: Optional[str] = None,
    ) -> None:
        api_doc_type = self._normalize_doc_type(doc_type)
        payload: Dict[str, Any] = {
            "docType": api_doc_type,
            "recordId": int(record_id),
        }
        if oss_key is not None:
            payload["ossKey"] = oss_key
        self._post(f"/rag/records/{action}", payload, timeout=self._write_timeout)

    @staticmethod
    def _normalize_ts_code_for_http(raw, *, scenario_name: str):
        """ts_code → HTTP 端可序列化形态。

        - str: 原样上送
        - list: 原样上送
        - dict: 原样上送（type=list/select + values/conditions）
        - TsCodeFilter 实例: 拆解为 dict 上送
        - None: 不上送（调用方跳过此字段）
        """
        if raw is None:
            return None
        if isinstance(raw, TsCodeFilter):
            if raw.type == "list":
                return {"type": "list", "values": list(raw.values)}
            if raw.type == "select":
                return {"type": "select", "conditions": dict(raw.conditions)}
            return None
        if isinstance(raw, (str, list, dict)):
            return raw
        raise TypeError(
            f"ts_code 必须是 str / list / dict / TsCodeFilter，scenario={scenario_name!r} 收到 {type(raw).__name__}"
        )

    @staticmethod
    def _renormalize_date_field(records: List[Dict[str, Any]], date_field: str):
        """对服务端返回的 BIGINT 毫秒保持原样（与旧 psycopg2 行为一致），
        字段名统一为 date_field。

        旧 psycopg2 实现 ann_date/trade_date 返回的就是毫秒整数，HTTP 实现也维持一致，
        避免 run.py process_announcements/process_reports 的 .get(...) 行为变化。
        """
        return records
