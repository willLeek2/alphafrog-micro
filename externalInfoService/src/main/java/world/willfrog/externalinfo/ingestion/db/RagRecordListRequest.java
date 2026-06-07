package world.willfrog.externalinfo.ingestion.db;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * RAG 待处理记录查询请求体。
 *
 * <p>对应 fetch_scripts/rag_ingestion 侧 DbClient.get_unprocessed_announcements /
 * get_unprocessed_reports 的所有过滤条件。
 *
 * <p>tsCode 字段支持三种形态（与 Python 侧 TsCodeFilter.from_yaml 对齐）：
 * <ul>
 *   <li>{@code null}：不过滤</li>
 *   <li>{@code String}：单个 ts_code 精确匹配</li>
 *   <li>{@code List<String>}：多个 ts_code IN 列表</li>
 *   <li>{@code Map<String,Object>}：结构化配置，
 *       例：{@code {"type":"select", "conditions":{"index_codes":["000300.SH"], "member_date_from":"20240101"}}}
 *       → 走 EXISTS 子查询</li>
 * </ul>
 */
@Data
public class RagRecordListRequest {

    /** 文档类型："announcement" | "research_report" */
    private String docType;

    /** 单次最多返回条数，默认 50 */
    private Integer limit = 50;

    /** SQL OFFSET，默认 0 */
    private Integer offset = 0;

    /** 起始日期（YYYYMMDD 字符串），与 docType 配合映射到 ann_date / trade_date */
    private String dateFrom;

    /** 截止日期（YYYYMMDD 字符串，含当天），同上 */
    private String dateTo;

    /**
     * ts_code 过滤，支持 str / list / Map{type,conditions} / null。
     * Jackson 反序列化为 Object，具体形态由 service 层判定。
     */
    private Object tsCode;

    /**
     * title LIKE 模糊匹配模式列表（OR 关系）。
     * 列表为空或 null 时不应用此过滤。
     */
    private List<String> titlePatterns;
}
