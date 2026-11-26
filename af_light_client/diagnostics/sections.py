"""按运行编号读取各段材料。请求固定跳过制品的惰性注册。"""

from .read_client import DEFAULT_SECTIONS, base_section_jobs, save_base_section

__all__ = ["DEFAULT_SECTIONS", "base_section_jobs", "save_base_section"]
