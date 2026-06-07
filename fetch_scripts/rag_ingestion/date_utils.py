"""
RAG ingestion 共享的日期工具函数。
"""
from datetime import datetime, timezone, timedelta

# DB 中 ann_date / trade_date 以 Asia/Shanghai (UTC+8) 午夜的 Unix 毫秒时间戳存储,
# 与 Java 侧 RagAnnouncementDao.parseDateToMs 保持一致。
_CST = timezone(timedelta(hours=8))


def yyyymmdd_to_ms(date_str: str) -> int:
    """将 YYYYMMDD 日期字符串转为对应 Asia/Shanghai 午夜的 Unix 毫秒时间戳。"""
    dt = datetime.strptime(date_str, "%Y%m%d").replace(tzinfo=_CST)
    return int(dt.timestamp() * 1000)
