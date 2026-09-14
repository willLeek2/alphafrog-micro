package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunCreditRecordMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunCreditSettlementSummaryMessage;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunCreditsResponse;
import world.willfrog.alphafrogmicro.common.dao.agent.AgentRunCreditSummaryDao;
import world.willfrog.alphafrogmicro.common.dao.agent.AgentRunLlmCallCreditDao;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentRunCreditSummary;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentRunLlmCallCredit;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AgentRunCreditQueryService {

    private static final String STATUS_SETTLED = "SETTLED";
    private static final String STATUS_PENDING_RETRY = "PENDING_RETRY";
    private static final String STATUS_MISSING = "MISSING";

    private final AgentRunCreditSummaryDao summaryDao;
    private final AgentRunLlmCallCreditDao callCreditDao;
    private final ObjectMapper objectMapper;

    public GetAgentRunCreditsResponse build(AgentRun run) {
        if (run == null || isBlank(run.getId())) {
            throw new IllegalArgumentException("run not found");
        }
        AgentRunCreditSummary summary = summaryDao.findByRunId(run.getId());
        List<AgentRunLlmCallCredit> records = callCreditDao.listByRunId(run.getId());

        // 按 llmCallId 去重，取最高 attempt 的记录作为「effective」状态；
        // 让 attempt=2 终态覆盖 attempt=1 PENDING_RETRY 占位，避免 summary 计数被滞留 pending 污染。
        Map<String, AgentRunLlmCallCredit> effectiveByCallId = dedupByLatestAttempt(records);

        String currency = firstNonBlank(summary == null ? null : summary.getCurrency(),
                firstRecordCurrency(records), "USD");
        BigDecimal totalCredits = summary == null || summary.getTotalCreditConsumed() == null
                ? sumCreditDelta(effectiveByCallId.values())
                : summary.getTotalCreditConsumed();

        GetAgentRunCreditsResponse.Builder builder = GetAgentRunCreditsResponse.newBuilder()
                .setRunId(nvl(run.getId()))
                .setOwnerUserId(nvl(run.getUserId()))
                .setTotalCredits(decimalString(totalCredits))
                .setCurrency(currency)
                .setUpdatedAt(toDateString(summary == null ? run.getUpdatedAt() : summary.getUpdatedAt()));

        int immediateCount = 0;
        int delayedCount = 0;
        int pendingCount = 0;
        int missingCount = 0;

        for (AgentRunLlmCallCredit effective : effectiveByCallId.values()) {
            String status = nvl(effective.getSettlementStatus());
            int attempt = effective.getSettlementAttempt() == null ? 0 : effective.getSettlementAttempt();
            if (STATUS_SETTLED.equalsIgnoreCase(status)) {
                if (attempt <= 1) {
                    immediateCount++;
                } else {
                    delayedCount++;
                }
            } else if (STATUS_PENDING_RETRY.equalsIgnoreCase(status)) {
                pendingCount++;
            } else if (STATUS_MISSING.equalsIgnoreCase(status)) {
                missingCount++;
            }
        }

        // 详细 records 列表保留全部原始记录（包含 attempt=1 PENDING_RETRY 占位），
        // 方便前端展示完整的两阶段结算时间线，但 summary 计数走去重后的结果。
        for (AgentRunLlmCallCredit record : records) {
            int attempt = record.getSettlementAttempt() == null ? 0 : record.getSettlementAttempt();
            Map<String, Object> ext = readExtMap(record.getExt());
            builder.addRecords(AgentRunCreditRecordMessage.newBuilder()
                    .setCallId(nvl(record.getLlmCallId()))
                    .setPhase(str(ext.get("phase")))
                    .setTodoId(str(ext.get("todoId")))
                    .setEndpoint(nvl(record.getEndpointName()))
                    .setModel(nvl(record.getModelName()))
                    .setCostSource(nvl(record.getCostSource()))
                    .setCurrency(nvl(firstNonBlank(record.getCurrency(), currency)))
                    .setCostAmount(decimalString(record.getCostAmount()))
                    .setCreditDelta(decimalString(record.getCreditDelta()))
                    .setSettlementAttempt(attempt)
                    .setSettlementStatus(nvl(record.getSettlementStatus()))
                    .setReason(nvl(record.getReason()))
                    .setCreatedAt(toDateString(record.getCreatedAt()))
                    .build());
        }

        builder.setSummary(AgentRunCreditSettlementSummaryMessage.newBuilder()
                .setImmediateCount(immediateCount)
                .setDelayedCount(delayedCount)
                .setPendingCount(pendingCount)
                .setMissingCount(missingCount)
                .setTotalCallCount(effectiveByCallId.size())
                .setCurrency(currency)
                .setTotalCredits(decimalString(totalCredits))
                .setLastSettlementAt(toDateString(summary == null ? null : summary.getLastSettlementAt()))
                .build());
        return builder.build();
    }

    /**
     * 按 llmCallId 去重：相同 callId 下取 attempt 更大的那条为 effective 记录。
     * 让 attempt=2 终态（SETTLED / MISSING）覆盖 attempt=1 留下的 PENDING_RETRY 占位。
     * llmCallId 为空的脏记录被跳过。
     */
    private Map<String, AgentRunLlmCallCredit> dedupByLatestAttempt(List<AgentRunLlmCallCredit> records) {
        Map<String, AgentRunLlmCallCredit> byCallId = new HashMap<>();
        if (records == null || records.isEmpty()) {
            return byCallId;
        }
        for (AgentRunLlmCallCredit record : records) {
            if (record == null || isBlank(record.getLlmCallId())) {
                continue;
            }
            AgentRunLlmCallCredit existing = byCallId.get(record.getLlmCallId());
            if (existing == null) {
                byCallId.put(record.getLlmCallId(), record);
                continue;
            }
            int existingAttempt = existing.getSettlementAttempt() == null ? 0 : existing.getSettlementAttempt();
            int newAttempt = record.getSettlementAttempt() == null ? 0 : record.getSettlementAttempt();
            if (newAttempt > existingAttempt) {
                byCallId.put(record.getLlmCallId(), record);
            }
        }
        return byCallId;
    }

    private BigDecimal sumCreditDelta(Iterable<AgentRunLlmCallCredit> records) {
        BigDecimal sum = BigDecimal.ZERO;
        for (AgentRunLlmCallCredit record : records) {
            if (record.getCreditDelta() != null) {
                sum = sum.add(record.getCreditDelta());
            }
        }
        return sum;
    }

    private String firstRecordCurrency(List<AgentRunLlmCallCredit> records) {
        for (AgentRunLlmCallCredit record : records) {
            if (!isBlank(record.getCurrency())) {
                return record.getCurrency();
            }
        }
        return null;
    }

    private Map<String, Object> readExtMap(String json) {
        if (isBlank(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String decimalString(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private String toDateString(OffsetDateTime value) {
        return value == null ? "" : value.toString();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
