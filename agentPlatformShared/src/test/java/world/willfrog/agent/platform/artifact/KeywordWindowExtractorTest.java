package world.willfrog.agent.platform.artifact;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 014-opt-10：关键词命中窗口提取器（{@link KeywordWindowExtractor}）的边界用例。
 *
 * <p>直接测 extract 与 zeroHitMessage：零命中、命中数超过 maxHits 的截断与续查、
 * 窗口重叠/相接合并与不相邻分段、charBudget 不足时的首段必出与延后、检索起点
 * 跳过、radius 越界 clamp、maxHits=1 等极端值。服务层口径（keyword 空白走分页、
 * totalLength 恒为全文长度、配置读取与 200/20 回退）归
 * ToolOutputRefServiceImplTest 与 RunRawRefStoreImplTest。</p>
 */
class KeywordWindowExtractorTest {

    // ===== ① 零命中 =====

    @Test
    void extractWithoutHitShouldReturnEmptyResult() {
        KeywordWindowExtractor.Result result = KeywordWindowExtractor.extract(
                "hello world", "zzz", 0, 5, 10, 100);

        assertEquals("", result.text());
        assertEquals(0, result.hitCount());
        assertEquals(0, result.returnedHits());
        assertEquals(-1, result.nextIndex());
        assertFalse(result.hasMore());
    }

    @Test
    void extractWithSearchStartBeyondContentShouldReturnZeroHit() {
        // 检索起点越过全文末尾：检索范围为空，与找不到关键词同款零命中语义
        KeywordWindowExtractor.Result result = KeywordWindowExtractor.extract(
                "hello world", "hello", 50, 5, 10, 100);

        assertEquals("", result.text());
        assertEquals(0, result.hitCount());
        assertEquals(0, result.returnedHits());
        assertEquals(-1, result.nextIndex());
        assertFalse(result.hasMore());
    }

    @Test
    void zeroHitMessageShouldContainKeywordAndTotalLength() {
        // 服务层把零命中的空文本换成这条提示：关键词与全文长度都要出现
        assertEquals("关键词\"cash\"未命中：全文共 12345 字符。可更换关键词，或去掉 keyword 后按 offset 分段读取。",
                KeywordWindowExtractor.zeroHitMessage("cash", 12345));
    }

    // ===== ③ 窗口重叠/相接合并成一段、不相邻命中分段 =====

    @Test
    void overlappingWindowsShouldMergeIntoOneSegment() {
        // 命中 1 与 4（radius=3）的窗口 [0,6) 与 [1,7) 重叠：合并成一段，段头两个偏移
        KeywordWindowExtractor.Result result = KeywordWindowExtractor.extract(
                "aKWbKWc", "KW", 0, 3, 10, 1000);

        assertEquals("@1 @4\naKWbKWc", result.text());
        assertEquals(2, result.hitCount());
        assertEquals(2, result.returnedHits());
        assertEquals(-1, result.nextIndex());
    }

    @Test
    void exactlyAdjacentWindowsShouldMergeIntoOneSegment() {
        // 命中 0 与 8（radius=3）：第二窗起点 8-3=5 恰好等于第一窗终点 0+2+3=5，
        // 相接（不重叠）也合并，段头保留两个命中偏移
        KeywordWindowExtractor.Result result = KeywordWindowExtractor.extract(
                "KW123456KW", "KW", 0, 3, 10, 1000);

        assertEquals("@0 @8\nKW123456KW", result.text());
        assertEquals(2, result.hitCount());
        assertEquals(2, result.returnedHits());
        assertFalse(result.hasMore());
    }

    @Test
    void nonAdjacentWindowsShouldStaySeparateSegments() {
        // 命中 0 与 9（radius=3）：第二窗起点 6 > 第一窗终点 5，不合并——
        // 两段之间以空行（\n\n）分隔，各段段头只标自己的偏移
        KeywordWindowExtractor.Result result = KeywordWindowExtractor.extract(
                "KW1234567KW", "KW", 0, 3, 10, 1000);

        assertEquals("@0\nKW123\n\n@9\n567KW", result.text());
        assertEquals(2, result.hitCount());
        assertEquals(2, result.returnedHits());
        assertEquals(-1, result.nextIndex());
    }

    // ===== ② 命中数超过 maxHits：截断 + nextIndex 续查 =====

    @Test
    void hitsBeyondMaxHitsShouldTruncateAndResumeByNextIndex() {
        // 5 个命中（下标 0/9/18/27/36），maxHits=2：hitCount 不受截断影响（仍报 5），
        // returnedHits=2，nextIndex 指向第一处未返回命中（18）
        String content = "KW.......KW.......KW.......KW.......KW";
        KeywordWindowExtractor.Result first = KeywordWindowExtractor.extract(
                content, "KW", 0, 3, 2, 100);

        assertEquals("@0\nKW...\n\n@9\n...KW...", first.text());
        assertEquals(5, first.hitCount());
        assertEquals(2, first.returnedHits());
        assertEquals(18, first.nextIndex());
        assertTrue(first.hasMore());

        // 续查一：从 nextIndex=18 起，检索范围内只剩 3 个命中，仍按 maxHits=2 截断
        KeywordWindowExtractor.Result second = KeywordWindowExtractor.extract(
                content, "KW", 18, 3, 2, 100);

        assertEquals("@18\n...KW...\n\n@27\n...KW...", second.text());
        assertEquals(3, second.hitCount());
        assertEquals(2, second.returnedHits());
        assertEquals(36, second.nextIndex());
        assertTrue(second.hasMore());

        // 续查二：从 36 起，1 个命中全部返回，nextIndex=-1 收口
        KeywordWindowExtractor.Result third = KeywordWindowExtractor.extract(
                content, "KW", 36, 3, 2, 100);

        assertEquals("@36\n...KW", third.text());
        assertEquals(1, third.hitCount());
        assertEquals(1, third.returnedHits());
        assertEquals(-1, third.nextIndex());
        assertFalse(third.hasMore());
    }

    // ===== ④ charBudget 不足：首段必出、后续段留给下一次调用 =====

    @Test
    void budgetTooSmallShouldReturnFirstSegmentOnlyAndDeferRest() {
        // 3 个命中（0/9/18）分成三段，各段渲染后长 8/11/10；预算 15 只装得下第一段：
        // 第二段 8+2+11=21 > 15，停止，段留给下一次调用
        String content = "KW.......KW.......KW";
        KeywordWindowExtractor.Result result = KeywordWindowExtractor.extract(
                content, "KW", 0, 3, 10, 15);

        assertEquals("@0\nKW...", result.text());
        assertEquals(3, result.hitCount());
        assertEquals(1, result.returnedHits());
        assertEquals(9, result.nextIndex());
        assertTrue(result.hasMore());
    }

    @Test
    void firstSegmentShouldBeReturnedEvenBeyondBudget() {
        // 预算缩到 1（远小于第一段的 8 字符）：首段必出，保证同参数续查始终有前进
        KeywordWindowExtractor.Result result = KeywordWindowExtractor.extract(
                "KW.......KW.......KW", "KW", 0, 3, 10, 1);

        assertEquals("@0\nKW...", result.text());
        assertEquals(3, result.hitCount());
        assertEquals(1, result.returnedHits());
        assertEquals(9, result.nextIndex());
        assertTrue(result.hasMore());
    }

    @Test
    void budgetExactlyFittingShouldIncludeFollowingSegment() {
        // 预算 21 恰好等于两段拼接总长（8+2+11）：装得下整段就返回（边界用 > 判停），
        // 第三段（10 字符）留给下一次调用
        KeywordWindowExtractor.Result result = KeywordWindowExtractor.extract(
                "KW.......KW.......KW", "KW", 0, 3, 10, 21);

        assertEquals("@0\nKW...\n\n@9\n...KW...", result.text());
        assertEquals(2, result.returnedHits());
        assertEquals(18, result.nextIndex());
        assertTrue(result.hasMore());
    }

    @Test
    void deferredSegmentsShouldComeBackOnResumeCalls() {
        // 承接 budgetTooSmallShouldReturnFirstSegmentOnlyAndDeferRest：
        // 从 nextIndex=9 续查拿到第二段（预算 15 仍装不下第三段），再续查收口
        String content = "KW.......KW.......KW";
        KeywordWindowExtractor.Result second = KeywordWindowExtractor.extract(
                content, "KW", 9, 3, 10, 15);

        assertEquals("@9\n...KW...", second.text());
        assertEquals(2, second.hitCount());
        assertEquals(1, second.returnedHits());
        assertEquals(18, second.nextIndex());

        KeywordWindowExtractor.Result third = KeywordWindowExtractor.extract(
                content, "KW", 18, 3, 10, 15);

        assertEquals("@18\n...KW", third.text());
        assertEquals(1, third.hitCount());
        assertEquals(-1, third.nextIndex());
        assertFalse(third.hasMore());
    }

    // ===== ⑤ offset（searchStart）作为查找起点 =====

    @Test
    void searchStartShouldSkipEarlierHitsAndCountRangeHitsOnly() {
        // 命中 0/6/12，从 6 起检索：命中 0 被跳过（不参与、也不并入段），
        // hitCount 只计检索范围内的 2 个
        KeywordWindowExtractor.Result result = KeywordWindowExtractor.extract(
                "KW aa KW bb KW", "KW", 6, 3, 10, 1000);

        assertEquals("@6 @12\naa KW bb KW", result.text());
        assertEquals(2, result.hitCount());
        assertEquals(2, result.returnedHits());
        assertEquals(-1, result.nextIndex());
    }

    @Test
    void searchStartInsideEarlierHitShouldSkipThatHit() {
        // 检索起点落在第一个命中内部（下标 1 在 "KW" 的 W 上）：该命中整段跳过
        KeywordWindowExtractor.Result result = KeywordWindowExtractor.extract(
                "KW aa KW bb KW", "KW", 1, 3, 10, 1000);

        assertEquals("@6 @12\naa KW bb KW", result.text());
        assertEquals(2, result.hitCount());
    }

    // ===== ⑨ 极端值 =====

    @Test
    void radiusBeyondContentLengthShouldClampWindowToWholeContent() {
        // radius=100 远大于全文：窗口 clamp 成全文，不越界
        KeywordWindowExtractor.Result result = KeywordWindowExtractor.extract(
                "abcdef", "cd", 0, 100, 10, 1000);

        assertEquals("@2\nabcdef", result.text());
        assertEquals(1, result.hitCount());
        assertEquals(-1, result.nextIndex());
    }

    @Test
    void hitAtContentStartShouldClampWindowStartToZero() {
        // 命中在全文开头：窗口起点 clamp 到 0（不出负数下标），
        // 终点为 0+2+3=5，窗口只到 "KWtai"
        KeywordWindowExtractor.Result result = KeywordWindowExtractor.extract(
                "KWtail", "KW", 0, 3, 10, 1000);

        assertEquals("@0\nKWtai", result.text());
    }

    @Test
    void hitAtContentEndShouldClampWindowEndToLength() {
        // 命中贴着全文结尾（下标 4，radius=3）：窗口终点 clamp 到 6，不越界
        KeywordWindowExtractor.Result result = KeywordWindowExtractor.extract(
                "headKW", "KW", 0, 3, 10, 1000);

        assertEquals("@4\neadKW", result.text());
    }

    @Test
    void maxHitsOneWithSingleHitShouldReturnOneSegment() {
        // maxHits=1、全文只有一个命中：单命中成段，正常收口
        KeywordWindowExtractor.Result result = KeywordWindowExtractor.extract(
                "oneKWtwo", "KW", 0, 10, 1, 1000);

        assertEquals("@3\noneKWtwo", result.text());
        assertEquals(1, result.hitCount());
        assertEquals(1, result.returnedHits());
        assertEquals(-1, result.nextIndex());
        assertFalse(result.hasMore());
    }

    @Test
    void maxHitsOneWithMultipleHitsShouldReturnFirstHitOnly() {
        // maxHits=1、两个命中：只返回第一个，nextIndex 指向第二个供续查
        KeywordWindowExtractor.Result result = KeywordWindowExtractor.extract(
                "KW.......KW", "KW", 0, 3, 1, 1000);

        assertEquals("@0\nKW...", result.text());
        assertEquals(2, result.hitCount());
        assertEquals(1, result.returnedHits());
        assertEquals(9, result.nextIndex());
        assertTrue(result.hasMore());
    }
}
