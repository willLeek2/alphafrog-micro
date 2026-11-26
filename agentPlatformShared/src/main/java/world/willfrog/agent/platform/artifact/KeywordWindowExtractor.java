package world.willfrog.agent.platform.artifact;

import java.util.ArrayList;
import java.util.List;

/**
 * 014-opt-10：rereadToolResult 关键词命中窗口提取器。
 *
 * <p>keyword 模式此前按行过滤（只保留包含关键词的行），但工具响应统一是
 * Jackson 序列化的单行紧凑 JSON，全文没有换行符，按行过滤形同未过滤，
 * 读取永远落在原文开头。本提取器改为命中窗口：定位关键词的每个出现位置，
 * 返回该位置前后各 radius 字符的窗口；前后范围重叠或相接的窗口合并成一段，
 * 段头标注段内每个命中的原文偏移（如 {@code @48210 @48333}），供下一次
 * 调用续查或去掉 keyword 按偏移精读。</p>
 *
 * <p>调用方保证 keyword 非空、charBudget 大于 0（配置缺省时取默认值）；
 * radius 与 maxHits 会被钳制到 {@value #MAX_RADIUS} 与 {@value #MAX_MAX_HITS}
 * 以内的正值——误配超大值时配合「首段必出」可能单次返回接近整篇原文，
 * 直接灌爆模型上下文。</p>
 */
final class KeywordWindowExtractor {

    private static final String SEGMENT_SEPARATOR = "\n\n";
    /** 窗口半径防呆上限（缺省 200 的 10 倍）。 */
    private static final int MAX_RADIUS = 2000;
    /** 命中数上限的防呆上限（缺省 20 的 5 倍）。 */
    private static final int MAX_MAX_HITS = 100;

    private KeywordWindowExtractor() {
    }

    /**
     * 提取结果。
     *
     * <ul>
     *   <li>{@code text}：窗口文本。段与段之间以空行分隔；每段两行——第一行
     *       是该段内每个命中的原文偏移，第二行是窗口原文。</li>
     *   <li>{@code hitCount}：本次检索范围内（searchStart 起向后）的命中总数。</li>
     *   <li>{@code returnedHits}：本次实际返回的命中数。</li>
     *   <li>{@code nextIndex}：下一处未返回命中的原文下标；全部返回后为 -1。</li>
     * </ul>
     */
    record Result(String text, int hitCount, int returnedHits, int nextIndex) {

        boolean hasMore() {
            return nextIndex >= 0;
        }
    }

    /** 命中数为 0 时返回给模型的提示文本：给出全文长度并指明两条出路。 */
    static String zeroHitMessage(String keyword, int totalLength) {
        return "关键词\"" + keyword + "\"未命中：全文共 " + totalLength
                + " 字符。可更换关键词，或去掉 keyword 后按 offset 分段读取。";
    }

    static Result extract(String content, String keyword, int searchStart,
                          int radius, int maxHits, int charBudget) {
        if (keyword == null || keyword.isEmpty()) {
            // 空关键词会命中每个位置（indexOf("") 不前进），必须显式拒绝而不是依赖调用方把关。
            throw new IllegalArgumentException("keyword 不能为空");
        }
        radius = Math.min(Math.max(radius, 1), MAX_RADIUS);
        maxHits = Math.min(Math.max(maxHits, 1), MAX_MAX_HITS);
        // 只保留前 maxHits+1 个命中下标：进窗口的最多 maxHits 个，多出的一个
        // 只用于算 nextIndex；其余命中只计数不装箱，高频关键词（如单字符）
        // 打在 8MB 原文上也不产生内存尖峰。
        List<Integer> keptHits = new ArrayList<>();
        int hitCount = 0;
        int cursor = Math.max(0, searchStart);
        while ((cursor = content.indexOf(keyword, cursor)) >= 0) {
            if (keptHits.size() <= maxHits) {
                keptHits.add(cursor);
            }
            hitCount++;
            cursor += keyword.length();
        }
        if (keptHits.isEmpty()) {
            return new Result("", 0, 0, -1);
        }
        // 命中数上限先落在命中清单上（不拆段），超出的命中由下一次调用续查。
        List<Integer> cappedHits = keptHits.subList(0, Math.min(keptHits.size(), maxHits));
        List<Segment> segments = mergeIntoSegments(content, keyword, radius, cappedHits);

        StringBuilder text = new StringBuilder();
        int returnedHits = 0;
        for (int i = 0; i < segments.size(); i++) {
            String piece = renderSegment(content, segments.get(i));
            // 首段必出：即使超出字符预算也返回，保证同参数续查始终有前进。
            // 后续段装不下整段时停止，该段留给下一次调用。
            if (i > 0 && text.length() + SEGMENT_SEPARATOR.length() + piece.length() > charBudget) {
                break;
            }
            if (i > 0) {
                text.append(SEGMENT_SEPARATOR);
            }
            text.append(piece);
            returnedHits += segments.get(i).hits.size();
        }

        int nextIndex = returnedHits < hitCount ? keptHits.get(returnedHits) : -1;
        return new Result(text.toString(), hitCount, returnedHits, nextIndex);
    }

    private static List<Segment> mergeIntoSegments(String content, String keyword,
                                                   int radius, List<Integer> hits) {
        List<Segment> segments = new ArrayList<>();
        for (int hit : hits) {
            int start = Math.max(0, hit - radius);
            int end = Math.min(content.length(), hit + keyword.length() + radius);
            Segment last = segments.isEmpty() ? null : segments.get(segments.size() - 1);
            if (last != null && start <= last.end) {
                // 前后范围重叠或相接：合并成一段，段内保留每个命中的原文偏移。
                last.end = Math.max(last.end, end);
                last.hits.add(hit);
                continue;
            }
            Segment segment = new Segment(start, end);
            segment.hits.add(hit);
            segments.add(segment);
        }
        return segments;
    }

    private static String renderSegment(String content, Segment segment) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < segment.hits.size(); i++) {
            if (i > 0) {
                text.append(' ');
            }
            text.append('@').append(segment.hits.get(i));
        }
        text.append('\n');
        text.append(content, segment.start, segment.end);
        return text.toString();
    }

    private static final class Segment {
        final int start;
        int end;
        final List<Integer> hits = new ArrayList<>();

        Segment(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }
}
