package world.willfrog.agent.tools.market.advanced;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AdvancedSearchRequest 工厂方法")
class AdvancedSearchRequestTest {

    private static final String TOOL_NAME = "searchAssetInfo";

    private static AdvancedSearchException invalid(ThrowingRunnable r) {
        return assertThrows(AdvancedSearchException.class, r::run);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    @Nested
    @DisplayName("simple 模式不进入 advanced")
    class IsAdvancedMapDetection {

        @Test
        @DisplayName("纯 keyword 输入不应识别为 advanced")
        void keywordOnlyNotAdvanced() {
            assertFalse(AdvancedSearchRequest.isAdvancedMap(Map.of("keyword", "X")));
        }

        @Test
        @DisplayName("显式 mode=simple 不应识别为 advanced")
        void explicitSimpleModeNotAdvanced() {
            assertFalse(AdvancedSearchRequest.isAdvancedMap(Map.of("mode", "simple", "keyword", "X")));
        }

        @Test
        @DisplayName("顶层 mode=advanced 应识别为 advanced")
        void topLevelAdvancedDetected() {
            assertTrue(AdvancedSearchRequest.isAdvancedMap(Map.of("mode", "advanced", "keyword", "X")));
        }

        @Test
        @DisplayName("query 内 mode=advanced 应识别为 advanced")
        void nestedAdvancedDetected() {
            assertTrue(AdvancedSearchRequest.isAdvancedMap(Map.of("query", Map.of("mode", "advanced"))));
        }

        @Test
        @DisplayName("null 参数应识别为非 advanced")
        void nullParamsNotAdvanced() {
            assertFalse(AdvancedSearchRequest.isAdvancedMap(null));
        }
    }

    @Nested
    @DisplayName("顶层 mode=advanced 正常解析")
    class TopLevelModeAdvanced {

        private final ObjectMapper objectMapper = new ObjectMapper();

        @Test
        @DisplayName("完整 ETF 查询字段映射到 request 与 canonicalQuery")
        void parsesTopLevelAdvancedFields() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("mode", "advanced");
            params.put("asset_type", "ETF");
            params.put("name", "沪深300");
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("name", "沪深300");
            query.put("conditions", List.of(Map.of(
                    "type", "has_stock",
                    "stock_code", "000001.SZ",
                    "start_date", "20240101",
                    "end_date", "20241231"
            )));
            params.put("query", query);

            AdvancedSearchRequest req = AdvancedSearchRequest.from(TOOL_NAME, params, objectMapper);

            assertEquals(TOOL_NAME, req.getToolName());
            assertEquals("etf", req.getAssetType());
            assertEquals("沪深300", req.getName());
            assertEquals(1, req.getConditions().size());
            assertEquals("has_stock", req.getConditions().get(0).getType());
            assertEquals("000001.SZ", req.getConditions().get(0).getStockCode());

            assertEquals("沪深300", req.getCanonicalQuery().get("name"));
            assertEquals(1, ((List<?>) req.getCanonicalQuery().get("conditions")).size());
        }
    }

    @Nested
    @DisplayName("query 内嵌套 mode=advanced")
    class NestedModeAdvanced {

        private final ObjectMapper objectMapper = new ObjectMapper();

        @Test
        @DisplayName("query.mode=advanced 应展开为顶层")
        void parsesNestedAdvancedFields() {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("mode", "advanced");
            query.put("asset_type", "stock");
            query.put("name", "银行");
            query.put("conditions", List.of(Map.of(
                    "type", "index_component",
                    "index_code", "000300.SH",
                    "start_date", "20240101",
                    "end_date", "20241231"
            )));
            Map<String, Object> params = Map.of("query", query);

            AdvancedSearchRequest req = AdvancedSearchRequest.from(TOOL_NAME, params, objectMapper);

            assertEquals("stock", req.getAssetType());
            assertEquals("银行", req.getName());
            assertEquals("index_component", req.getConditions().get(0).getType());
            assertEquals("000300.SH", req.getConditions().get(0).getIndexCode());
        }
    }

    @Nested
    @DisplayName("query 作为 JSON 字符串")
    class QueryAsJsonString {

        private final ObjectMapper objectMapper = new ObjectMapper();

        @Test
        @DisplayName("query 字符串应被反序列化后用于解析")
        void parsesQueryJsonString() throws Exception {
            Map<String, Object> inner = new LinkedHashMap<>();
            inner.put("mode", "advanced");
            inner.put("name", "Y");
            inner.put("conditions", List.of());
            Map<String, Object> params = Map.of(
                    "mode", "advanced",
                    "query", objectMapper.writeValueAsString(inner)
            );

            AdvancedSearchRequest req = AdvancedSearchRequest.from(TOOL_NAME, params, objectMapper);

            assertEquals("Y", req.getName());
            assertTrue(req.getConditions().isEmpty());
            assertTrue(((List<?>) req.getCanonicalQuery().get("conditions")).isEmpty());
        }
    }

    @Nested
    @DisplayName("name 与 conditions 的非阻塞组合")
    class NameOrConditionsSufficient {

        private final ObjectMapper objectMapper = new ObjectMapper();

        @Test
        @DisplayName("仅有 name 不应抛异常")
        void nameOnlyShouldPass() {
            Map<String, Object> params = Map.of(
                    "mode", "advanced",
                    "name", "沪深300"
            );

            AdvancedSearchRequest req = AdvancedSearchRequest.from(TOOL_NAME, params, objectMapper);

            assertEquals("沪深300", req.getName());
            assertTrue(req.getConditions().isEmpty());
            assertTrue(((List<?>) req.getCanonicalQuery().get("conditions")).isEmpty());
        }

        @Test
        @DisplayName("仅有 conditions 不应抛异常")
        void conditionsOnlyShouldPass() {
            Map<String, Object> params = Map.of(
                    "mode", "advanced",
                    "conditions", List.of(Map.of(
                            "type", "has_stock",
                            "stock_code", "000001.SZ",
                            "start_date", "20240101",
                            "end_date", "20241231"
                    ))
            );

            AdvancedSearchRequest req = AdvancedSearchRequest.from(TOOL_NAME, params, objectMapper);

            assertNotNull(req.getName());
            assertTrue(req.getName().isBlank());
            assertEquals(1, req.getConditions().size());
            assertEquals(1, ((List<?>) req.getCanonicalQuery().get("conditions")).size());
        }
    }

    @Nested
    @DisplayName("缺少 mode 时的拒绝行为")
    class MissingMode {

        private final ObjectMapper objectMapper = new ObjectMapper();

        @Test
        @DisplayName("纯 keyword 输入应抛 INVALID_ARGUMENT")
        void keywordOnlyRejected() {
            Map<String, Object> params = Map.of("keyword", "沪深300");

            AdvancedSearchException ex = invalid(() ->
                    AdvancedSearchRequest.from(TOOL_NAME, params, objectMapper));

            assertEquals("INVALID_ARGUMENT", ex.getCode());
            assertTrue(ex.getMessage().contains("mode=advanced is required"),
                    "异常消息应包含 mode=advanced is required，实际: " + ex.getMessage());
        }
    }

    @Nested
    @DisplayName("name 与 conditions 都为空时的拒绝行为")
    class BothBlank {

        private final ObjectMapper objectMapper = new ObjectMapper();

        @Test
        @DisplayName("空 body 应抛 INVALID_ARGUMENT")
        void emptyAdvancedBodyRejected() {
            Map<String, Object> params = Map.of("mode", "advanced");

            AdvancedSearchException ex = invalid(() ->
                    AdvancedSearchRequest.from(TOOL_NAME, params, objectMapper));

            assertEquals("INVALID_ARGUMENT", ex.getCode());
            assertTrue(ex.getMessage().contains("advanced query requires name or conditions"),
                    "异常消息应包含 advanced query requires name or conditions，实际: " + ex.getMessage());
        }
    }

    @Nested
    @DisplayName("conditions 类型校验")
    class ConditionsTypeValidation {

        private final ObjectMapper objectMapper = new ObjectMapper();

        @Test
        @DisplayName("conditions 非数组应抛 INVALID_ARGUMENT")
        void nonArrayConditionsRejected() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("mode", "advanced");
            params.put("name", "X");
            params.put("conditions", "not-an-array");

            AdvancedSearchException ex = invalid(() ->
                    AdvancedSearchRequest.from(TOOL_NAME, params, objectMapper));

            assertEquals("INVALID_ARGUMENT", ex.getCode());
            assertTrue(ex.getMessage().contains("query.conditions must be an array"),
                    "异常消息应包含 query.conditions must be an array，实际: " + ex.getMessage());
        }
    }

    @Nested
    @DisplayName("asset_type 大小写与多值")
    class AssetTypeNormalization {

        private final ObjectMapper objectMapper = new ObjectMapper();

        @Test
        @DisplayName("asset_type=ETF 应小写为 etf")
        void uppercaseEtfLowercased() {
            Map<String, Object> params = Map.of(
                    "mode", "advanced",
                    "name", "X",
                    "asset_type", "ETF"
            );

            AdvancedSearchRequest req = AdvancedSearchRequest.from(TOOL_NAME, params, objectMapper);

            assertEquals("etf", req.getAssetType());
        }

        @Test
        @DisplayName("assetType=Stock 应小写为 stock")
        void camelCaseStockLowercased() {
            Map<String, Object> params = Map.of(
                    "mode", "advanced",
                    "name", "X",
                    "assetType", "Stock"
            );

            AdvancedSearchRequest req = AdvancedSearchRequest.from(TOOL_NAME, params, objectMapper);

            assertEquals("stock", req.getAssetType());
        }

        @Test
        @DisplayName("assetTypes 多值应取第一段并小写")
        void multiValueAssetTypesFirstSegment() {
            Map<String, Object> params = Map.of(
                    "mode", "advanced",
                    "name", "X",
                    "assetTypes", "INDEX,stock"
            );

            AdvancedSearchRequest req = AdvancedSearchRequest.from(TOOL_NAME, params, objectMapper);

            assertEquals("index", req.getAssetType());
        }

        @Test
        @DisplayName("asset_type=fund 在工厂层不被拒绝，原样小写返回")
        void unknownAssetTypePassesThroughLowercased() {
            Map<String, Object> params = Map.of(
                    "mode", "advanced",
                    "name", "X",
                    "asset_type", "fund"
            );

            AdvancedSearchRequest req = AdvancedSearchRequest.from(TOOL_NAME, params, objectMapper);

            assertEquals("fund", req.getAssetType());
        }
    }

    @Nested
    @DisplayName("canonicalQuery 字段命名")
    class CanonicalQueryFieldNames {

        private final ObjectMapper objectMapper = new ObjectMapper();

        @Test
        @DisplayName("canonicalQuery 顶层与 condition 行内字段名应为 snake_case")
        void canonicalKeysAreSnakeCase() {
            Map<String, Object> params = Map.of(
                    "mode", "advanced",
                    "conditions", List.of(Map.of(
                            "type", "has_stock",
                            "stock_code", "000001.SZ",
                            "start_date", "20240101",
                            "end_date", "20241231"
                    ))
            );

            AdvancedSearchRequest req = AdvancedSearchRequest.from(TOOL_NAME, params, objectMapper);

            Map<String, Object> canonical = req.getCanonicalQuery();
            assertEquals(List.of("name", "conditions"), new java.util.ArrayList<>(canonical.keySet()));

            List<Map<String, Object>> rows = (List<Map<String, Object>>) canonical.get("conditions");
            assertEquals(1, rows.size());
            Map<String, Object> row = rows.get(0);
            assertEquals(
                    List.of("type", "index_code", "stock_code", "start_date", "end_date", "min_weight", "max_weight"),
                    new java.util.ArrayList<>(row.keySet())
            );
            assertEquals("has_stock", row.get("type"));
            assertEquals("000001.SZ", row.get("stock_code"));
            assertEquals("20240101", row.get("start_date"));
            assertEquals("20241231", row.get("end_date"));
        }
    }

    @Nested
    @DisplayName("conditions 来源优先级")
    class ConditionsSourcePrecedence {

        private final ObjectMapper objectMapper = new ObjectMapper();

        @Test
        @DisplayName("query.conditions 应优先于 root.conditions")
        void queryConditionsWinOverRootConditions() {
            Map<String, Object> goodCondition = new LinkedHashMap<>();
            goodCondition.put("type", "has_stock");
            goodCondition.put("stock_code", "000001.SZ");
            goodCondition.put("start_date", "20240101");
            goodCondition.put("end_date", "20241231");

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("mode", "advanced");
            params.put("name", "A");
            params.put("conditions", List.of("bad-c1"));
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("conditions", List.of(goodCondition));
            params.put("query", query);

            AdvancedSearchRequest req = AdvancedSearchRequest.from(TOOL_NAME, params, objectMapper);

            assertEquals(1, req.getConditions().size());
            assertEquals("has_stock", req.getConditions().get(0).getType());
            assertEquals("000001.SZ", req.getConditions().get(0).getStockCode());
        }
    }

    @Nested
    @DisplayName("query JSON 字符串解析失败")
    class QueryJsonParseFailure {

        private final ObjectMapper objectMapper = new ObjectMapper();

        @Test
        @DisplayName("非法 query JSON 应抛 INVALID_ARGUMENT")
        void malformedQueryJsonRejected() {
            Map<String, Object> params = Map.of(
                    "mode", "advanced",
                    "query", "{not valid json"
            );

            AdvancedSearchException ex = invalid(() ->
                    AdvancedSearchRequest.from(TOOL_NAME, params, objectMapper));

            assertEquals("INVALID_ARGUMENT", ex.getCode());
            assertTrue(ex.getMessage().contains("query JSON is invalid"),
                    "异常消息应包含 query JSON is invalid，实际: " + ex.getMessage());
        }
    }

    @Nested
    @DisplayName("condition 字段校验向上抛出")
    class ConditionValidationPropagated {

        private final ObjectMapper objectMapper = new ObjectMapper();

        @Test
        @DisplayName("非法 start_date 应抛 INVALID_ARGUMENT")
        void badStartDatePropagates() {
            Map<String, Object> params = Map.of(
                    "mode", "advanced",
                    "conditions", List.of(Map.of(
                            "type", "has_stock",
                            "start_date", "bad",
                            "end_date", "20241231"
                    ))
            );

            AdvancedSearchException ex = invalid(() ->
                    AdvancedSearchRequest.from(TOOL_NAME, params, objectMapper));

            assertEquals("INVALID_ARGUMENT", ex.getCode());
            assertNotNull(ex.getMessage());
        }
    }
}
