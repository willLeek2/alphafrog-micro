"""
RAG ingestion 的 ts_code 过滤抽象。

支持两种过滤来源:
- list: 直接给一个 ts_code 列表
- select: 从 alphafrog_index_weight 表中按指数 + 成分股日期范围选

YAML 配置示例:
    ts_code:
      type: list
      values: ["600519.SH", "601318.SH"]
    # 或
    ts_code:
      type: select
      conditions:
        index_codes: ["000300.SH", "000905.SH"]
        member_date_from: "20200101"
        member_date_to: "20241231"

也兼容旧写法 (str / list / None)。
"""
from dataclasses import dataclass, field
from typing import Literal, Union

from date_utils import yyyymmdd_to_ms


class ConfigError(ValueError):
    """配置解析或校验失败时抛此异常, fail closed 不会静默通过。"""


@dataclass
class TsCodeFilter:
    type: Literal["list", "select", None] = None
    values: list[str] = field(default_factory=list)
    conditions: dict = field(default_factory=dict)

    @classmethod
    def from_yaml(
        cls,
        raw: Union[str, list, dict, None],
        *,
        scenario_name: str = "(未命名)",
    ) -> "TsCodeFilter":
        if raw is None:
            return cls()

        # 兼容旧式: ts_code: "000001.SZ" (str)
        if isinstance(raw, str):
            return cls(type="list", values=[raw])

        # 兼容旧式: ts_code: ["000001.SZ", "000002.SZ"] (list)
        if isinstance(raw, list):
            if not raw:
                raise ConfigError(
                    f"scenario {scenario_name!r}: ts_code list 不能为空"
                )
            return cls(type="list", values=list(raw))

        if isinstance(raw, dict):
            t = raw.get("type")
            if t == "list":
                values = raw.get("values") or []
                if not values:
                    raise ConfigError(
                        f"scenario {scenario_name!r}: type=list 时 values 必填且非空"
                    )
                return cls(type="list", values=list(values))

            if t == "select":
                conds = raw.get("conditions") or {}
                idx_codes = conds.get("index_codes") or []
                if not idx_codes:
                    raise ConfigError(
                        f"scenario {scenario_name!r}: type=select 时 "
                        f"conditions.index_codes 必填且非空"
                    )
                # 把 conditions 复制一份, 避免外部修改引用
                return cls(type="select", conditions=dict(conds))

            raise ConfigError(
                f"scenario {scenario_name!r}: ts_code.type 必须是 list 或 select, 收到 {t!r}"
            )

        raise ConfigError(
            f"scenario {scenario_name!r}: ts_code 格式不支持 {type(raw).__name__}"
        )

    def to_sql_clause(
        self, *, asset_code_column: str = "d.ts_code"
    ) -> tuple[str, list]:
        """生成 WHERE 子句片段 + 参数, 用于嵌入主 SQL。

        asset_code_column: 外层查询的资产代码列完整限定名, 例如 "d.ts_code"。
        两个调用点 (announcement / research) 都用同一个别名 d。
        """
        if self.type == "list":
            ph = ",".join(["%s"] * len(self.values))
            return f"{asset_code_column} IN ({ph})", list(self.values)

        if self.type == "select":
            conds: list[str] = []
            params: list = []
            idx_codes: list[str] = self.conditions["index_codes"]
            idx_ph = ",".join(["%s"] * len(idx_codes))
            conds.append(f"w.index_code IN ({idx_ph})")
            params.extend(idx_codes)
            if "member_date_from" in self.conditions:
                conds.append("w.trade_date >= %s")
                params.append(yyyymmdd_to_ms(self.conditions["member_date_from"]))
            if "member_date_to" in self.conditions:
                conds.append("w.trade_date < %s")
                params.append(
                    yyyymmdd_to_ms(self.conditions["member_date_to"]) + 86_400_000
                )
            where = " AND ".join(conds)
            return (
                f"EXISTS (SELECT 1 FROM alphafrog_index_weight w "
                f"WHERE w.con_code = {asset_code_column} AND {where})",
                params,
            )

        return "", []
