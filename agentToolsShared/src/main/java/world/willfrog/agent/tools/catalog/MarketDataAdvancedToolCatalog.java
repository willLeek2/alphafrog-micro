package world.willfrog.agent.tools.catalog;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        return List.copyOf(byName.values());
    }

    public static ToolSpecification searchIndexSpec() {
        return ToolSpecification.builder()
                .name("searchIndex")
                .description("按关键词搜索指数；也支持 mode=advanced，通过 query.conditions 做指数成分条件筛选。simple 模式继续使用 keyword。advanced 仅支持 has_stock 条件；日期必须传 YYYYMMDD 格式的日期字符串或 NONE。对 index_component/has_stock 的 NONE/NONE 默认取最新公告期完整快照，单边 NONE 表示不限制该侧边界。")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("keyword", "simple 模式指数关键词，如 沪深300；可用 | 批量。")
                        .addStringProperty("mode", "可选：advanced。省略时为 simple。")
                        .addProperty("query", advancedQuerySchema("has_stock"))
                        .additionalProperties(true)
                        .build())
                .build();
    }

    public static ToolSpecification searchAssetInfoSpec() {
        return ToolSpecification.builder()
                .name("searchAssetInfo")
                .description("统一搜索股票/ETF/指数/场外基金基本信息；也支持 mode=advanced。advanced 要求 asset_type=stock|etf，stock 支持 index_component，etf 支持 has_stock。simple 模式继续使用 query + assetTypes。对 index_component 的 NONE/NONE 默认取最新公告期完整快照，单边 NONE 表示不限制该侧边界。")
                .parameters(JsonObjectSchema.builder()
                        .addProperty("query", JsonAnyOfSchema.builder()
                                .description("simple 模式传关键词字符串；advanced 模式传对象 {name, conditions}。")
                                .anyOf(
                                        JsonStringSchema.builder().description("simple 模式关键词。").build(),
                                        advancedQuerySchema("index_component 或 has_stock")
                                )
                                .build())
                        .addStringProperty("assetTypes", "simple 模式资产类型，stock,etf,index,off_exchange_fund。")
                        .addStringProperty("asset_type", "advanced 模式资产类型：stock 或 etf。")
                        .addStringProperty("marketScope", "仅支持 domestic。")
                        .addStringProperty("mode", "可选：advanced。省略时为 simple。")
                        .addProperty("conditions", conditionsSchema("index_component 或 has_stock"))
                        .additionalProperties(true)
                        .build())
                .build();
    }

    private static JsonSchemaElement advancedQuerySchema(String conditionTypes) {
        return JsonObjectSchema.builder()
                .description("advanced 查询对象。name 可选；conditions 之间为 AND，单个 condition 中 code 使用 | 分隔多个代码，代表 OR 条件。")
                .addStringProperty("name", "可选名称关键词，先走 simple 搜索候选。")
                .addProperty("conditions", conditionsSchema(conditionTypes))
                .additionalProperties(false)
                .build();
    }

    private static JsonSchemaElement conditionsSchema(String conditionTypes) {
        return JsonArraySchema.builder()
                .description("advanced 条件数组。支持 " + conditionTypes + "；conditions 之间为 AND 关系。")
                .items(JsonObjectSchema.builder()
                        .addStringProperty("type", "index_component 或 has_stock。")
                        .addStringProperty("index_code", "index_component 使用，可用 | 分隔多个指数代码，代表 OR 条件。")
                        .addStringProperty("stock_code", "has_stock 使用，可用 | 分隔多个股票代码，代表 OR 条件。")
                        .addStringProperty("start_date", "日期边界，传 YYYYMMDD 格式的日期字符串；NONE 表示未指定。对 index_component 的 NONE/NONE 默认取最新公告期完整快照，单边 NONE 表示不限制该侧边界。")
                        .addStringProperty("end_date", "日期边界，传 YYYYMMDD 格式的日期字符串；NONE 表示未指定。对 index_component 的 NONE/NONE 默认取最新公告期完整快照，单边 NONE 表示不限制该侧边界。")
                        .addProperty("min_weight", JsonNumberSchema.builder().description("可选最小权重，取值范围 0.00-1.00。").build())
                        .addProperty("max_weight", JsonNumberSchema.builder().description("可选最大权重，取值范围 0.00-1.00。").build())
                        .additionalProperties(false)
                        .build())
                .build();
    }
}
