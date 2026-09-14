package world.willfrog.agent.tools.catalog;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import world.willfrog.agent.platform.service.ToolDescriptionTexts;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MarketDataAdvancedToolCatalog {

    private MarketDataAdvancedToolCatalog() {
    }

    public static List<ToolSpecification> mergeCanonical(List<ToolSpecification> specifications) {
        Map<String, ToolSpecification> byName = new LinkedHashMap<>();
        if (specifications != null) {
            for (ToolSpecification spec : specifications) {
                if (spec != null && spec.name() != null && !spec.name().isBlank()) {
                    byName.put(spec.name(), spec);
                }
            }
        }
        byName.put("searchIndex", searchIndexSpec());
        byName.put("searchAssetInfo", searchAssetInfoSpec());
        byName.put("getExchangeAssetDaily", getExchangeAssetDailySpec());
        return List.copyOf(byName.values());
    }

    /**
     * 返回本 helper 以 canonical schema 覆盖的工具名集合，用于与 {@code AgentToolRegistry} 的
     * {@code canonicalSpec=MARKET_ADVANCED} 声明做契约对照。
     */
    public static Set<String> overriddenCanonicalNames() {
        Set<String> names = new LinkedHashSet<>();
        names.add("searchIndex");
        names.add("searchAssetInfo");
        names.add("getExchangeAssetDaily");
        return Collections.unmodifiableSet(names);
    }

    public static ToolSpecification searchIndexSpec() {
        return ToolSpecification.builder()
                .name("searchIndex")
                .description(ToolDescriptionTexts.require("searchIndex"))
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("keyword", "simple 模式指数关键词，如 沪深300；可用 | 批量。advanced 模式忽略。")
                        .addStringProperty("mode", "可选：advanced。省略时为 simple。")
                        .addProperty("advancedQuery", advancedQuerySchema("has_stock"))
                        .additionalProperties(true)
                        .build())
                .build();
    }

    public static ToolSpecification searchAssetInfoSpec() {
        return ToolSpecification.builder()
                .name("searchAssetInfo")
                .description(ToolDescriptionTexts.require("searchAssetInfo"))
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("query", "simple 模式关键词，支持 | 批量或 JSON 数组。advanced 模式忽略。")
                        .addStringProperty("assetTypes", "simple 模式资产类型，stock,etf,index,off_exchange_fund。")
                        .addStringProperty("marketScope", "仅支持 domestic。")
                        .addStringProperty("mode", "可选：advanced。省略时为 simple。")
                        .addProperty("advancedQuery", advancedQuerySchema("index_component / has_stock / sw_industry_l2_component / sw_industry_l3_component"))
                        .additionalProperties(true)
                        .build())
                .build();
    }

    public static ToolSpecification getExchangeAssetDailySpec() {
        return ToolSpecification.builder()
                .name("getExchangeAssetDaily")
                .description(ToolDescriptionTexts.require("getExchangeAssetDaily"))
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("tsCode", "simple 模式场内资产代码，支持 | 批量；advanced 模式忽略。")
                        .addStringProperty("assetType", "simple 模式必填 stock|etf|index。advanced 模式忽略，asset_type 放在 advancedQuery 里。")
                        .addStringProperty("startDate", "日线日期范围起点，YYYYMMDD。")
                        .addStringProperty("endDate", "日线日期范围终点，YYYYMMDD。")
                        .addStringProperty("priceMode", "目前仅支持 raw_ohlc。")
                        .addStringProperty("mode", "可选：advanced。省略时为 simple。")
                        .addProperty("advancedQuery", advancedQuerySchema("index_component / sw_industry_l2_component / sw_industry_l3_component"))
                        .additionalProperties(true)
                        .build())
                .build();
    }

    private static JsonSchemaElement advancedQuerySchema(String conditionTypes) {
        return JsonObjectSchema.builder()
                .description("advanced 查询对象。asset_type 对 searchAssetInfo / getExchangeAssetDaily advanced 必填；name 可选；conditions 之间为 AND，单个 condition 中 code 使用 | 分隔多个代码，代表 OR 条件。")
                .addStringProperty("asset_type", "advanced 模式资产类型：stock / etf / index；searchAssetInfo / getExchangeAssetDaily advanced 必填，searchIndex advanced 忽略。")
                .addStringProperty("name", "可选名称关键词，先走 simple 搜索候选。")
                .addProperty("conditions", conditionsSchema(conditionTypes))
                .additionalProperties(false)
                .build();
    }

    private static JsonSchemaElement conditionsSchema(String conditionTypes) {
        return JsonArraySchema.builder()
                .description("advanced 条件数组。支持 " + conditionTypes + "；conditions 之间为 AND 关系。")
                .items(JsonObjectSchema.builder()
                        .addStringProperty("type", "index_component / sw_industry_l2_component / sw_industry_l3_component / has_stock。")
                        .addStringProperty("index_code", "index_component 使用，可用 | 分隔多个指数代码，代表 OR 条件。")
                        .addStringProperty("stock_code", "has_stock 使用，可用 | 分隔多个股票代码，代表 OR 条件。")
                        .addStringProperty("industry_code", "sw_industry_l2_component / sw_industry_l3_component 使用，可用 | 分隔多个行业代码，代表 OR 条件。")
                        .addStringProperty("start_date", "日期边界，传 YYYYMMDD 格式的日期字符串；NONE 表示未指定。对 index_component 的 NONE/NONE 默认取最新公告期完整快照，单边 NONE 表示不限制该侧边界。")
                        .addStringProperty("end_date", "日期边界，传 YYYYMMDD 格式的日期字符串；NONE 表示未指定。对 index_component 的 NONE/NONE 默认取最新公告期完整快照，单边 NONE 表示不限制该侧边界。")
                        .addProperty("min_weight", JsonNumberSchema.builder().description("可选最小权重，取值范围 0.00-1.00。").build())
                        .addProperty("max_weight", JsonNumberSchema.builder().description("可选最大权重，取值范围 0.00-1.00。").build())
                        .additionalProperties(false)
                        .build())
                .build();
    }
}
