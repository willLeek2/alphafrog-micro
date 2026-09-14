package world.willfrog.agent.tools.market.advanced;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AdvancedSearchCondition 工厂与匹配逻辑")
class AdvancedSearchConditionTest {

    private static Map<String, Object> base(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "has_stock");
        for (int i = 0; i < kv.length; i += 2) {
            String key = (String) kv[i];
            m.put(key, kv[i + 1]);
        }
        return m;
    }

    @Nested
    @DisplayName("NONE 边界默认值")
    class NoneBoundaryDefaults {

        @Test
        @DisplayName("NONE NONE 退回到全局最大区间")
        void noneNoneFallsBackToGlobalRange() {
            AdvancedSearchCondition c = AdvancedSearchCondition.from(0, base());

            assertAll(
                    () -> assertEquals(19000101L, c.effectiveStartDate()),
                    () -> assertEquals(20991231L, c.effectiveEndDate()),
                    () -> assertNull(c.getStartDateValue()),
                    () -> assertNull(c.getEndDateValue())
            );
        }

        @Test
        @DisplayName("startDate 显式 NONE 取最大值")
        void startDateExplicitNone() {
            AdvancedSearchCondition c = AdvancedSearchCondition.from(0, base("end_date", "20241231"));

            assertEquals(19000101L, c.effectiveStartDate());
            assertEquals(20241231L, c.effectiveEndDate());
        }

        @Test
        @DisplayName("endDate 显式 NONE 取最小值")
        void endDateExplicitNone() {
            AdvancedSearchCondition c = AdvancedSearchCondition.from(0, base("start_date", "20240101"));

            assertEquals(20240101L, c.effectiveStartDate());
            assertEquals(20991231L, c.effectiveEndDate());
        }
    }

    @Nested
    @DisplayName("NONE 大小写敏感")
    class NoneCaseSensitive {

        @Test
        @DisplayName("小写 none 抛 INVALID_ARGUMENT")
        void lowercaseNoneRejected() {
            AdvancedSearchException ex = assertThrows(
                    AdvancedSearchException.class,
                    () -> AdvancedSearchCondition.from(0, base("start_date", "none"))
            );
            assertEquals("INVALID_ARGUMENT", ex.getCode());
            assertTrue(ex.getMessage().contains("YYYYMMDD or NONE"));
        }

        @Test
        @DisplayName("混合大小写 None 抛 INVALID_ARGUMENT")
        void mixedCaseNoneRejected() {
            for (String variant : List.of("None", "NoNe", "nONE")) {
                AdvancedSearchException ex = assertThrows(
                        AdvancedSearchException.class,
                        () -> AdvancedSearchCondition.from(0, base("start_date", variant))
                );
                assertEquals("INVALID_ARGUMENT", ex.getCode());
                assertTrue(ex.getMessage().contains("YYYYMMDD or NONE"));
            }
        }

        @Test
        @DisplayName("全大写 NONE 合法")
        void uppercaseNoneAccepted() {
            AdvancedSearchCondition c = AdvancedSearchCondition.from(0, base("start_date", "NONE"));

            assertNull(c.getStartDateValue());
            assertEquals(19000101L, c.effectiveStartDate());
        }
    }

    @Nested
    @DisplayName("date 格式非法边界")
    class DateFormatValidation {

        @Test
        @DisplayName("2月30日抛 valid YYYYMMDD")
        void invalidCalendarDateRejected() {
            AdvancedSearchException ex = assertThrows(
                    AdvancedSearchException.class,
                    () -> AdvancedSearchCondition.from(0, base("start_date", "20240230"))
            );
            assertEquals("INVALID_ARGUMENT", ex.getCode());
            assertTrue(ex.getMessage().contains("valid YYYYMMDD"));
        }

        @Test
        @DisplayName("带分隔符抛 YYYYMMDD or NONE")
        void dashSeparatedRejected() {
            AdvancedSearchException ex = assertThrows(
                    AdvancedSearchException.class,
                    () -> AdvancedSearchCondition.from(0, base("start_date", "2024-02-15"))
            );
            assertEquals("INVALID_ARGUMENT", ex.getCode());
            assertTrue(ex.getMessage().contains("YYYYMMDD or NONE"));
        }

        @Test
        @DisplayName("含字母抛 YYYYMMDD or NONE")
        void nonNumericRejected() {
            AdvancedSearchException ex = assertThrows(
                    AdvancedSearchException.class,
                    () -> AdvancedSearchCondition.from(0, base("start_date", "2024023X"))
            );
            assertEquals("INVALID_ARGUMENT", ex.getCode());
            assertTrue(ex.getMessage().contains("YYYYMMDD or NONE"));
        }

        @Test
        @DisplayName("斜杠分隔抛 YYYYMMDD or NONE")
        void slashSeparatedRejected() {
            AdvancedSearchException ex = assertThrows(
                    AdvancedSearchException.class,
                    () -> AdvancedSearchCondition.from(0, base("start_date", "2024/02/15"))
            );
            assertEquals("INVALID_ARGUMENT", ex.getCode());
            assertTrue(ex.getMessage().contains("YYYYMMDD or NONE"));
        }

        @Test
        @DisplayName("常规 YYYYMMDD 解析正确")
        void standardDateParses() {
            AdvancedSearchCondition c = AdvancedSearchCondition.from(0, base("start_date", "20240101"));

            assertEquals(Long.valueOf(20240101L), c.getStartDateValue());
            assertEquals(20240101L, c.effectiveStartDate());
        }

        @Test
        @DisplayName("20991231 与 19000101 边界值合法")
        void boundaryDatesAccepted() {
            AdvancedSearchCondition max = AdvancedSearchCondition.from(0, base("start_date", "20991231"));
            AdvancedSearchCondition min = AdvancedSearchCondition.from(0, base("start_date", "19000101"));

            assertEquals(Long.valueOf(20991231L), max.getStartDateValue());
            assertEquals(Long.valueOf(19000101L), min.getStartDateValue());
        }

        @Test
        @DisplayName("空字符串与 null 视为 NONE")
        void emptyOrNullDefaultsToNone() {
            AdvancedSearchCondition empty = AdvancedSearchCondition.from(0, base("start_date", ""));
            AdvancedSearchCondition absent = AdvancedSearchCondition.from(0, base());

            assertNull(empty.getStartDateValue());
            assertNull(absent.getStartDateValue());
        }
    }

    @Nested
    @DisplayName("start 与 end 顺序校验")
    class DateRangeOrdering {

        @Test
        @DisplayName("start > end 抛 INVALID_ARGUMENT")
        void startAfterEndRejected() {
            AdvancedSearchException ex = assertThrows(
                    AdvancedSearchException.class,
                    () -> AdvancedSearchCondition.from(0, base(
                            "start_date", "20240301",
                            "end_date", "20240201"))
            );
            assertEquals("INVALID_ARGUMENT", ex.getCode());
            assertTrue(ex.getMessage().contains("start_date must be <= end_date"));
        }

        @Test
        @DisplayName("start == end 合法")
        void startEqualsEndAllowed() {
            AdvancedSearchCondition c = AdvancedSearchCondition.from(0, base(
                    "start_date", "20240301",
                    "end_date", "20240301"));

            assertEquals(20240301L, c.effectiveStartDate());
            assertEquals(20240301L, c.effectiveEndDate());
        }
    }

    @Nested
    @DisplayName("weight 边界解析")
    class WeightBoundaries {

        @Test
        @DisplayName("单点区间 min == max 合法")
        void minEqualsMaxAccepted() {
            AdvancedSearchCondition c = AdvancedSearchCondition.from(0, base(
                    "min_weight", 0.5,
                    "max_weight", 0.5));

            assertEquals(0.5, c.getMinWeight());
            assertEquals(0.5, c.getMaxWeight());
        }

        @Test
        @DisplayName("min > max 抛 INVALID_ARGUMENT")
        void minGreaterThanMaxRejected() {
            AdvancedSearchException ex = assertThrows(
                    AdvancedSearchException.class,
                    () -> AdvancedSearchCondition.from(0, base(
                            "min_weight", 0.5,
                            "max_weight", 0.3))
            );
            assertEquals("INVALID_ARGUMENT", ex.getCode());
            assertTrue(ex.getMessage().contains("min_weight must be <= max_weight"));
        }

        @Test
        @DisplayName("负权重合法")
        void negativeWeightAccepted() {
            AdvancedSearchCondition c = AdvancedSearchCondition.from(0, base(
                    "min_weight", -1.0,
                    "max_weight", 1.0));

            assertEquals(-1.0, c.getMinWeight());
            assertEquals(1.0, c.getMaxWeight());
        }

        @Test
        @DisplayName("字符串数字解析为 Double")
        void stringNumericWeight() {
            AdvancedSearchCondition c = AdvancedSearchCondition.from(0, base("min_weight", "0.5"));

            assertEquals(Double.valueOf(0.5), c.getMinWeight());
        }

        @Test
        @DisplayName("非数字字符串抛 INVALID_ARGUMENT")
        void nonNumericWeightRejected() {
            AdvancedSearchException ex = assertThrows(
                    AdvancedSearchException.class,
                    () -> AdvancedSearchCondition.from(0, base("min_weight", "abc"))
            );
            assertEquals("INVALID_ARGUMENT", ex.getCode());
            assertTrue(ex.getMessage().contains("weight boundaries must be numeric"));
        }

        @Test
        @DisplayName("仅 max 缺省也视为有 weight 过滤")
        void maxOnlyStillFilters() {
            AdvancedSearchCondition c = AdvancedSearchCondition.from(0, base("min_weight", 0.5));

            assertTrue(c.hasWeightFilter());
        }

        @Test
        @DisplayName("无 weight 字段时不启用过滤")
        void noWeightFieldsNoFilter() {
            AdvancedSearchCondition c = AdvancedSearchCondition.from(0, base());

            assertFalse(c.hasWeightFilter());
        }
    }

    @Nested
    @DisplayName("matchesWeight 区间逻辑")
    class MatchesWeightLogic {

        @Test
        @DisplayName("无边界任何 weight 都匹配")
        void noBoundsAnyWeightMatches() {
            AdvancedSearchCondition c = AdvancedSearchCondition.from(0, base());

            assertAll(
                    () -> assertTrue(c.matchesWeight(0.0)),
                    () -> assertTrue(c.matchesWeight(0.5)),
                    () -> assertTrue(c.matchesWeight(999.0))
            );
        }

        @Test
        @DisplayName("仅有 min 时匹配闭区间下界")
        void minOnlyClosedLower() {
            AdvancedSearchCondition c = AdvancedSearchCondition.from(0, base("min_weight", 0.5));

            assertAll(
                    () -> assertFalse(c.matchesWeight(0.3)),
                    () -> assertTrue(c.matchesWeight(0.5)),
                    () -> assertTrue(c.matchesWeight(1.0))
            );
        }

        @Test
        @DisplayName("仅有 max 时匹配闭区间上界")
        void maxOnlyClosedUpper() {
            AdvancedSearchCondition c = AdvancedSearchCondition.from(0, base("max_weight", 0.5));

            assertAll(
                    () -> assertTrue(c.matchesWeight(0.5)),
                    () -> assertFalse(c.matchesWeight(0.6))
            );
        }

        @Test
        @DisplayName("双边界为闭区间")
        void bothBoundsClosedInterval() {
            AdvancedSearchCondition c = AdvancedSearchCondition.from(0, base(
                    "min_weight", 0.3,
                    "max_weight", 0.7));

            assertAll(
                    () -> assertTrue(c.matchesWeight(0.3)),
                    () -> assertTrue(c.matchesWeight(0.5)),
                    () -> assertTrue(c.matchesWeight(0.7)),
                    () -> assertFalse(c.matchesWeight(0.2)),
                    () -> assertFalse(c.matchesWeight(0.8))
            );
        }
    }

    @Nested
    @DisplayName("type 必填")
    class TypeRequired {

        @Test
        @DisplayName("缺少 type 抛 INVALID_ARGUMENT")
        void missingTypeRejected() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("start_date", "20240101");
            m.put("end_date", "20241231");

            AdvancedSearchException ex = assertThrows(
                    AdvancedSearchException.class,
                    () -> AdvancedSearchCondition.from(0, m)
            );
            assertEquals("INVALID_ARGUMENT", ex.getCode());
            assertTrue(ex.getMessage().contains("condition.type is required"));
        }

        @Test
        @DisplayName("type 空字符串抛 INVALID_ARGUMENT")
        void blankTypeRejected() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "   ");

            AdvancedSearchException ex = assertThrows(
                    AdvancedSearchException.class,
                    () -> AdvancedSearchCondition.from(0, m)
            );
            assertEquals("INVALID_ARGUMENT", ex.getCode());
            assertTrue(ex.getMessage().contains("condition.type is required"));
        }
    }

    @Nested
    @DisplayName("condition 入参类型校验")
    class RawArgumentTypeCheck {

        @Test
        @DisplayName("String raw 抛 INVALID_ARGUMENT")
        void stringRawRejected() {
            AdvancedSearchException ex = assertThrows(
                    AdvancedSearchException.class,
                    () -> AdvancedSearchCondition.from(0, "oops")
            );
            assertEquals("INVALID_ARGUMENT", ex.getCode());
            assertTrue(ex.getMessage().contains("Each condition must be an object"));
        }

        @Test
        @DisplayName("List raw 抛 INVALID_ARGUMENT")
        void listRawRejected() {
            AdvancedSearchException ex = assertThrows(
                    AdvancedSearchException.class,
                    () -> AdvancedSearchCondition.from(0, List.of("a", "b"))
            );
            assertEquals("INVALID_ARGUMENT", ex.getCode());
            assertTrue(ex.getMessage().contains("Each condition must be an object"));
        }

        @Test
        @DisplayName("null raw 抛 INVALID_ARGUMENT")
        void nullRawRejected() {
            AdvancedSearchException ex = assertThrows(
                    AdvancedSearchException.class,
                    () -> AdvancedSearchCondition.from(0, (Object) null)
            );
            assertEquals("INVALID_ARGUMENT", ex.getCode());
            assertTrue(ex.getMessage().contains("Each condition must be an object"));
        }
    }

    @Nested
    @DisplayName("字段别名兼容")
    class FieldAliases {

        @Test
        @DisplayName("camelCase 别名解析有效")
        void camelCaseAliasesWork() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "has_stock");
            m.put("startDate", "20240101");
            m.put("endDate", "20241231");
            m.put("indexCode", "000300.SH");
            m.put("stockCode", "000001.SZ");
            m.put("minWeight", 0.1);
            m.put("maxWeight", 0.9);

            AdvancedSearchCondition c = AdvancedSearchCondition.from(2, m);

            assertAll(
                    () -> assertEquals(2, c.getIndex()),
                    () -> assertEquals("20240101", c.getStartDate()),
                    () -> assertEquals("20241231", c.getEndDate()),
                    () -> assertEquals("000300.SH", c.getIndexCode()),
                    () -> assertEquals("000001.SZ", c.getStockCode()),
                    () -> assertEquals(0.1, c.getMinWeight()),
                    () -> assertEquals(0.9, c.getMaxWeight())
            );
        }

        @Test
        @DisplayName("snake_case 别名解析有效")
        void snakeCaseAliasesWork() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "has_stock");
            m.put("start_date", "20240101");
            m.put("end_date", "20241231");
            m.put("index_code", "000300.SH");
            m.put("stock_code", "000001.SZ");
            m.put("min_weight", 0.1);
            m.put("max_weight", 0.9);

            AdvancedSearchCondition c = AdvancedSearchCondition.from(3, m);

            assertAll(
                    () -> assertEquals(3, c.getIndex()),
                    () -> assertEquals("20240101", c.getStartDate()),
                    () -> assertEquals("20241231", c.getEndDate()),
                    () -> assertEquals("000300.SH", c.getIndexCode()),
                    () -> assertEquals("000001.SZ", c.getStockCode()),
                    () -> assertEquals(0.1, c.getMinWeight()),
                    () -> assertEquals(0.9, c.getMaxWeight())
            );
        }

        @Test
        @DisplayName("snake_case 优先于 camelCase 同名字段")
        void snakeCaseWinsOverCamelCase() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "has_stock");
            m.put("startDate", "20240101");
            m.put("endDate", "20241231");
            m.put("start_date", "20240601");
            m.put("end_date", "20240630");

            AdvancedSearchCondition c = AdvancedSearchCondition.from(0, m);

            assertNotNull(c.getStartDate());
            assertNotNull(c.getEndDate());
        }
    }

    @Nested
    @DisplayName("type 归一化小写")
    class TypeLowercased {

        @Test
        @DisplayName("全大写 type 转小写")
        void uppercaseTypeLowercased() {
            AdvancedSearchCondition c = AdvancedSearchCondition.from(0, base("type", "HAS_STOCK"));

            assertEquals("has_stock", c.getType());
        }

        @Test
        @DisplayName("混合大小写 type 转小写")
        void mixedCaseTypeLowercased() {
            AdvancedSearchCondition c = AdvancedSearchCondition.from(0, base("type", "Index_Component"));

            assertEquals("index_component", c.getType());
        }
    }
}
