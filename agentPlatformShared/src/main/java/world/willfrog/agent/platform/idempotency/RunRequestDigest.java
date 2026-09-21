package world.willfrog.agent.platform.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 创建请求摘要：把请求指纹按固定规则规范化后取 SHA-256。
 *
 * <p>规范化规则：非 JSON 的字段原样参与；JSON 形式的字段（上下文、分阶段模型配置）先解析成树，
 * 对象的键按字典序重排、数字去掉尾随零（{@code 1.0} 与 {@code 1} 是同一个值），再序列化。
 * 于是客户端重排键序、改写空白或换数字写法都不会改变摘要，只有内容真的变了才变。</p>
 *
 * <p>解析不了的文本（例如根本没传 JSON）按原始字符串参与，不做猜测。输出是 64 位十六进制，
 * 正好落在库里 64 字符的摘要列上。</p>
 */
public final class RunRequestDigest {

    private RunRequestDigest() {
    }

    public static String digest(RunRequestFingerprint fingerprint, ObjectMapper objectMapper) {
        if (fingerprint == null) {
            throw new IllegalArgumentException("请求指纹不能为空");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("摘要需要对象映射器把指纹序列化成固定顺序的内容");
        }
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("userId", fingerprint.userId());
        ordered.put("message", fingerprint.message());
        ordered.put("contextJson", canonicalizeText(fingerprint.contextJson(), objectMapper));
        ordered.put("modelName", fingerprint.modelName());
        ordered.put("endpointName", fingerprint.endpointName());
        ordered.put("provider", fingerprint.provider());
        ordered.put("captureLlmRequests", fingerprint.captureLlmRequests());
        ordered.put("stageConfigJson", canonicalizeText(fingerprint.stageConfigJson(), objectMapper));
        String canonical;
        try {
            canonical = objectMapper.writeValueAsString(ordered);
        } catch (Exception e) {
            throw new IllegalStateException("序列化创建请求指纹失败", e);
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前运行环境没有 SHA-256", e);
        }
    }

    /**
     * 文本字段的规范化：能解析成 JSON 就按规范化后的树参与，解析不了就原样参与。
     *
     * <p>解析失败不做任何补救，也不抛错：客户端本来就可以传一段普通文本。</p>
     */
    private static Object canonicalizeText(String text, ObjectMapper objectMapper) {
        if (text == null || text.isBlank()) {
            return text;
        }
        try {
            return canonicalize(objectMapper.readTree(text));
        } catch (Exception ignored) {
            return text;
        }
    }

    private static Object canonicalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> sorted = new TreeMap<>();
            node.properties().forEach(entry -> sorted.put(entry.getKey(), canonicalize(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            List<Object> elements = new ArrayList<>();
            node.forEach(element -> elements.add(canonicalize(element)));
            return elements;
        }
        if (node.isNumber()) {
            BigDecimal decimal = node.decimalValue().stripTrailingZeros();
            return decimal;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.textValue();
    }
}
