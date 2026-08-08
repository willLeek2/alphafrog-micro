package world.willfrog.agentlangchain.finance;

import java.util.List;

/**
 * 服务端生成的金融结果块。除 markdown 外全部是后台字段（事件去重/审计用），
 * 永不进入面向用户或模型的文本。
 */
public record FinanceResultBlock(
        String blockId,
        String markdown,
        List<String> recordIds,
        String environmentId,
        String rendererVersion) {
    public FinanceResultBlock {
        if (blockId == null || blockId.isBlank()) throw new IllegalArgumentException("blockId must not be blank");
        if (markdown == null || markdown.isBlank()) throw new IllegalArgumentException("markdown must not be blank");
        recordIds = recordIds == null ? List.of() : List.copyOf(recordIds);
    }
}
