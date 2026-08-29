package world.willfrog.alphafrogmicro.common.service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 把一次 Prompt 覆盖变更写成可查询的审计原因：谁改、改了哪些字段、从哪一版到哪一版。
 */
public final class PromptChangeAudit {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PromptChangeAudit() {
    }

    public static String reason(List<String> promptFields, String fromVersion, String toVersion) {
        ObjectNode node = MAPPER.createObjectNode();
        ArrayNode fields = node.putArray("promptFields");
        if (promptFields != null) {
            promptFields.forEach(fields::add);
        }
        if (fromVersion != null && !fromVersion.isBlank()) {
            node.put("fromVersion", fromVersion);
        }
        if (toVersion != null && !toVersion.isBlank()) {
            node.put("toVersion", toVersion);
        }
        return node.toString();
    }
}
