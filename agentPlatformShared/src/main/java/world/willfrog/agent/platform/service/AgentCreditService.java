package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.alphafrogmicro.common.dao.agent.AgentCreditApplicationDao;
import world.willfrog.alphafrogmicro.common.dao.agent.AgentCreditLedgerDao;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentCreditApplication;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentCreditLedger;
import world.willfrog.alphafrogmicro.common.pojo.user.User;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentCreditService {

    private static final String RESET_CYCLE = "monthly";

    private final UserDao userDao;
    private final AgentCreditApplicationDao creditApplicationDao;
    private final AgentCreditLedgerDao creditLedgerDao;
    private final AgentRunMapper runMapper;
    private final AgentEventService eventService;
    private final AgentModelCatalogService modelCatalogService;
    private final ObjectMapper objectMapper;

    @Value("${agent.credit.default-tool-cost:1}")
    private int defaultToolCost;

    @Value("${agent.credit.execute-python-cost:5}")
    private int executePythonCost;

    public CreditSummary getUserCredits(String userId) {
        Long userIdLong = parseUserId(userId);
        User user = userDao.getUserById(userIdLong);
        if (user == null) {
            throw new IllegalArgumentException("user not found");
        }
        int totalCredits = Math.max(0, decimalToInt(user.getCredit()));
        int usedCredits = Math.max(0, runMapper.sumCompletedCreditsByUser(userId));
        int remainingCredits = Math.max(0, totalCredits - usedCredits);
        String nextResetAt = nextResetAt();
        return new CreditSummary(totalCredits, remainingCredits, usedCredits, RESET_CYCLE, nextResetAt);
    }

    public ApplyCreditSummary applyCredits(String userId, int amount, String reason, String contact) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        Long userIdLong = parseUserId(userId);
        User user = userDao.getUserById(userIdLong);
        if (user == null) {
            throw new IllegalArgumentException("user not found");
        }

        // 创建 PENDING 状态的申请，不立即增加额度
        String applicationId = UUID.randomUUID().toString().replace("-", "");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AgentCreditApplication application = new AgentCreditApplication();
        application.setApplicationId(applicationId);
        application.setUserId(userId);
        application.setAmount(amount);
        application.setReason(nvl(reason));
        application.setContact(nvl(contact));
        application.setStatus("PENDING");
        application.setProcessedBy("");
        application.setProcessReason("");
        application.setVersion(0);
        application.setExt("{}");
        application.setProcessedAt(null); // 待审批
        creditApplicationDao.insert(application);

        CreditSummary summary = getUserCredits(userId);
        return new ApplyCreditSummary(
                applicationId,
                summary.totalCredits(),
                summary.remainingCredits(),
                summary.usedCredits(),
                "PENDING",
                now.truncatedTo(ChronoUnit.SECONDS).toString()
        );
    }

    public int calculateRunTotalCredits(AgentRun run, List<AgentRunEvent> events, String observabilityJson) {
        if (run == null) {
            return 0;
        }
        int toolCredits = 0;
        for (AgentRunEvent event : events == null ? List.<AgentRunEvent>of() : events) {
            if (event == null || !"TOOL_CALL_FINISHED".equals(event.getEventType())) {
                continue;
            }
            Map<String, Object> payload = readJsonMap(event.getPayloadJson());
            // D07：限流拒绝（未执行工具体）一律 0 credit；该判定优先于显式 credits 字段，
            // 防止未来某个发射点漏写 creditsConsumed=0 时按默认单价误扣。
            Boolean throttleRejected = toBoolean(payload.get("rejected_by_throttle"));
            if (Boolean.TRUE.equals(throttleRejected)) {
                continue;
            }
            String toolName = firstString(payload.get("toolName"), payload.get("tool_name"));
            // D07：元工具豁免（预算/业务 trace/credit），checkParallelLimits 不产生按次工具执行 credit
            if ("checkParallelLimits".equals(toolName)) {
                continue;
            }
            Integer payloadCredits = firstInt(payload.get("creditsConsumed"), payload.get("credits_consumed"));
            if (payloadCredits != null && payloadCredits >= 0) {
                toolCredits += payloadCredits;
                continue;
            }
            boolean cacheHit = extractCacheHit(payload);
            toolCredits += calculateToolCredits(toolName, cacheHit);
        }

        int totalTokens = extractTotalTokens(observabilityJson);
        if (totalTokens <= 0) {
            totalTokens = extractTotalTokensFromSnapshot(run.getSnapshotJson());
        }
        String modelName = extractExtField(run.getExt(), "model_name");
        double baseRate = modelCatalogService.resolveBaseRate(modelName);
        int llmCredits = calculateLlmCredits(totalTokens, baseRate);
        return Math.max(0, llmCredits + Math.max(0, toolCredits));
    }

    public int calculateRunTotalCredits(String runId, String userId, String observabilityJson) {
        AgentRun run = runMapper.findByIdAndUser(runId, userId);
        if (run == null) {
            return 0;
        }
        List<AgentRunEvent> events = eventService.listByRunId(runId);
        return calculateRunTotalCredits(run, events, observabilityJson);
    }

    public int calculateToolCredits(String toolName, boolean cacheHit) {
        if (cacheHit) {
            return 0;
        }
        if ("executePython".equalsIgnoreCase(nvl(toolName))) {
            return Math.max(0, executePythonCost);
        }
        return Math.max(0, defaultToolCost);
    }

    public int calculateLlmCredits(int totalTokens, double baseRate) {
        if (totalTokens <= 0) {
            return 0;
        }
        double safeRate = baseRate <= 0D ? 1.0D : baseRate;
        return (int) Math.ceil((totalTokens / 1000D) * safeRate);
    }

    public void recordRunConsumeLedger(String runId, String userId, int totalCreditsConsumed) {
        if (runId == null || runId.isBlank() || userId == null || userId.isBlank() || totalCreditsConsumed <= 0) {
            return;
        }
        Long userIdLong = parseUserId(userId);
        User user = userDao.getUserById(userIdLong);
        if (user == null) {
            return;
        }
        int totalCredits = Math.max(0, decimalToInt(user.getCredit()));
        int usedCreditsAfter = Math.max(0, runMapper.sumCompletedCreditsByUser(userId));
        int balanceAfter = Math.max(0, totalCredits - usedCreditsAfter);
        int balanceBefore = Math.max(0, balanceAfter + totalCreditsConsumed);

        AgentCreditLedger ledger = new AgentCreditLedger();
        ledger.setLedgerId(UUID.randomUUID().toString().replace("-", ""));
        ledger.setUserId(userId);
        ledger.setBizType("RUN_CONSUME");
        ledger.setDelta(-totalCreditsConsumed);
        ledger.setBalanceBefore(balanceBefore);
        ledger.setBalanceAfter(balanceAfter);
        ledger.setSourceType("AGENT_RUN");
        ledger.setSourceId(runId);
        ledger.setOperatorId("");
        ledger.setIdempotencyKey("");
        ledger.setReason("legacy_run_consume");
        ledger.setExt("{}");
        creditLedgerDao.insertIgnoreDuplicate(ledger);
    }

    public BigDecimal currentCreditBalance(String userId) {
        Long userIdLong = parseUserId(userId);
        BigDecimal ledgerBalance = creditLedgerDao.latestBalanceByUserId(userId);
        if (ledgerBalance != null) {
            return ledgerBalance.max(BigDecimal.ZERO);
        }
        User user = userDao.getUserById(userIdLong);
        if (user == null || user.getCredit() == null) {
            return BigDecimal.ZERO;
        }
        return user.getCredit().max(BigDecimal.ZERO);
    }

    public boolean hasPositiveCredit(String userId) {
        return currentCreditBalance(userId).compareTo(BigDecimal.ZERO) > 0;
    }

    private boolean extractCacheHit(Map<String, Object> payload) {
        Boolean direct = toBoolean(payload.get("cacheHit"));
        if (direct != null) {
            return direct;
        }
        direct = toBoolean(payload.get("cache_hit"));
        if (direct != null) {
            return direct;
        }
        Object cache = payload.get("cache");
        if (!(cache instanceof Map<?, ?> cacheMap)) {
            return false;
        }
        return Boolean.TRUE.equals(toBoolean(cacheMap.get("hit")));
    }

    private int extractTotalTokens(String observabilityJson) {
        if (observabilityJson == null || observabilityJson.isBlank()) {
            return 0;
        }
        Map<String, Object> observability = readJsonMap(observabilityJson);
        Object summary = observability.get("summary");
        if (!(summary instanceof Map<?, ?> summaryMap)) {
            return 0;
        }
        Integer totalTokens = firstInt(summaryMap.get("totalTokens"), summaryMap.get("total_tokens"));
        return totalTokens == null ? 0 : Math.max(0, totalTokens);
    }

    private int extractTotalTokensFromSnapshot(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return 0;
        }
        Map<String, Object> snapshot = readJsonMap(snapshotJson);
        Object observability = snapshot.get("observability");
        if (!(observability instanceof Map<?, ?> obsMap)) {
            return 0;
        }
        Object summary = obsMap.get("summary");
        if (!(summary instanceof Map<?, ?> summaryMap)) {
            return 0;
        }
        Integer totalTokens = firstInt(summaryMap.get("totalTokens"), summaryMap.get("total_tokens"));
        return totalTokens == null ? 0 : Math.max(0, totalTokens);
    }

    private String extractExtField(String extJson, String field) {
        Map<String, Object> ext = readJsonMap(extJson);
        Object value = ext.get(field);
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Integer firstInt(Object first, Object second) {
        Integer a = toInt(first);
        if (a != null) {
            return a;
        }
        return toInt(second);
    }

    private Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (Exception e) {
            return null;
        }
    }

    private String firstString(Object first, Object second) {
        String a = toText(first);
        if (!a.isBlank()) {
            return a;
        }
        return toText(second);
    }

    private String toText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Boolean toBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        return Boolean.parseBoolean(text);
    }

    private Long parseUserId(String userId) {
        String normalized = nvl(userId).trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("user_id is required");
        }
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("user_id must be numeric");
        }
    }

    private String nextResetAt() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return now.withDayOfMonth(1)
                .plusMonths(1)
                .withHour(0)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
                .toString();
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private int decimalToInt(BigDecimal value) {
        return value == null ? 0 : value.intValue();
    }

    /**
     * 批准额度申请
     */
    public ApplyCreditSummary approveApplication(String applicationId, String adminId) {
        AgentCreditApplication application = creditApplicationDao.getByApplicationId(applicationId);
        if (application == null) {
            throw new IllegalArgumentException("application not found");
        }
        if (!"PENDING".equals(application.getStatus())) {
            throw new IllegalStateException("application is not pending: " + application.getStatus());
        }

        Long userIdLong = parseUserId(application.getUserId());
        int updated = userDao.increaseCreditByUserId(userIdLong, application.getAmount());
        if (updated <= 0) {
            throw new IllegalStateException("failed to increase user credits");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int changed = creditApplicationDao.updateStatusWithVersion(
                applicationId,
                "APPROVED",
                now,
                nvl(adminId),
                "",
                application.getVersion() == null ? 0 : application.getVersion()
        );
        if (changed <= 0) {
            throw new IllegalStateException("failed to update application status");
        }

        CreditSummary summary = getUserCredits(application.getUserId());
        return new ApplyCreditSummary(
                applicationId,
                summary.totalCredits(),
                summary.remainingCredits(),
                summary.usedCredits(),
                "APPROVED",
                now.truncatedTo(ChronoUnit.SECONDS).toString()
        );
    }

    /**
     * 拒绝额度申请
     */
    public ApplyCreditSummary rejectApplication(String applicationId, String adminId) {
        AgentCreditApplication application = creditApplicationDao.getByApplicationId(applicationId);
        if (application == null) {
            throw new IllegalArgumentException("application not found");
        }
        if (!"PENDING".equals(application.getStatus())) {
            throw new IllegalStateException("application is not pending: " + application.getStatus());
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int changed = creditApplicationDao.updateStatusWithVersion(
                applicationId,
                "REJECTED",
                now,
                nvl(adminId),
                "",
                application.getVersion() == null ? 0 : application.getVersion()
        );
        if (changed <= 0) {
            throw new IllegalStateException("failed to update application status");
        }

        CreditSummary summary = getUserCredits(application.getUserId());
        return new ApplyCreditSummary(
                applicationId,
                summary.totalCredits(),
                summary.remainingCredits(),
                summary.usedCredits(),
                "REJECTED",
                now.truncatedTo(ChronoUnit.SECONDS).toString()
        );
    }

    public record CreditSummary(
            int totalCredits,
            int remainingCredits,
            int usedCredits,
            String resetCycle,
            String nextResetAt
    ) {
    }

    public record ApplyCreditSummary(
            String applicationId,
            int totalCredits,
            int remainingCredits,
            int usedCredits,
            String status,
            String appliedAt
    ) {
    }
}
