package world.willfrog.alphafrogmicro.common.service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 把一次 Prompt 覆盖变更写成可查询的审计原因：改了哪些字段，正文摘要从哪一版到哪一版。
 *
 * <p>语义版本是覆盖文档的稳定摘要；配置中心快照版本只作为传输元数据附带。</p>
 */
public final class PromptChangeAudit {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PromptChangeAudit() {
    }

    public static String reason(List<String> changedFields, String fromDigest, String toDigest,
                                String fromSnapshotVersion, String toSnapshotVersion) {
        ObjectNode node = MAPPER.createObjectNode();
        ArrayNode fields = node.putArray("promptFields");
        if (changedFields != null) {
            changedFields.forEach(fields::add);
        }
        if (fromDigest != null && !fromDigest.isBlank()) {
            node.put("fromDigest", fromDigest);
        }
        if (toDigest != null && !toDigest.isBlank()) {
            node.put("toDigest", toDigest);
        }
        if (fromSnapshotVersion != null && !fromSnapshotVersion.isBlank()) {
            node.put("fromVersion", fromSnapshotVersion);
        }
        if (toSnapshotVersion != null && !toSnapshotVersion.isBlank()) {
            node.put("toVersion", toSnapshotVersion);
        }
        return node.toString();
    }

    /** 兼容仍只传入快照版本的调用；快照版本只作为传输元数据。 */
    public static String reason(List<String> promptFields, String fromVersion, String toVersion) {
        return reason(promptFields, null, null, fromVersion, toVersion);
    }
}
