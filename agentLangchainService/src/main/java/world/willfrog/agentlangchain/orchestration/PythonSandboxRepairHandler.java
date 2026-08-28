package world.willfrog.agentlangchain.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.CodeRefineProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.PythonRepairContext;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.platform.service.CodeRefineLocalConfigLoader;
import world.willfrog.agentlangchain.tooljob.ToolJobResumeContext;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * executePython 的修复策略：用户代码非零退出才修，基础设施故障不修。
 * 对外错误码与事件字段名保持 python_repair_* 原样。
 */
@Component
public class PythonSandboxRepairHandler implements ToolRepairHandler {

    static final String EXHAUSTED_FAILURE_CODE = "python_repair_exhausted";
    static final String EXECUTE_REQUIRED_FAILURE_CODE = "python_repair_execute_required";

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final int MAX_ATTEMPTS_CAP = 10;
    private static final Set<String> REPAIRABLE_EXIT_REASONS = Set.of("NON_ZERO_EXIT");
    private static final ObjectMapper TOOL_RESULT_MAPPER = new ObjectMapper();

    private final AgentPromptService promptService;
    private final CodeRefineLocalConfigLoader configLoader;
    private final CodeRefineProperties startupProperties;

    public PythonSandboxRepairHandler() {
        this(null, null, null);
    }

    @Autowired
    public PythonSandboxRepairHandler(AgentPromptService promptService,
                                       @Autowired(required = false) CodeRefineLocalConfigLoader configLoader,
                                       @Autowired(required = false) CodeRefineProperties startupProperties) {
        this.promptService = promptService;
        this.configLoader = configLoader;
        this.startupProperties = startupProperties;
    }

    @Override
    public String toolName() {
        return ToolJobAnchor.EXECUTE_PYTHON_TOOL;
    }

    @Override
    public boolean supports(ToolJobResumeContext failure) {
        return failure != null
                && !failure.isTerminalSuccess()
                && "FAILED".equals(failure.getTerminalStatus())
                && Boolean.FALSE.equals(failure.getTerminalRetryable())
                && REPAIRABLE_EXIT_REASONS.contains(
                        nvl(failure.getTerminalExitReason(), "").toUpperCase(Locale.ROOT))
                && failure.getPythonFailedRequestFingerprints() != null
                && !failure.getPythonFailedRequestFingerprints().isEmpty();
    }

    @Override
    public boolean isRepairRound(ToolJobResumeContext context) {
        return context != null
                && !context.isTerminalSuccess()
                && currentAttempt(context) > 0
                && context.getPythonFailedRequestFingerprints() != null
                && !context.getPythonFailedRequestFingerprints().isEmpty();
    }

    @Override
    public boolean isActiveRepair(ToolJobResumeContext context) {
        return supports(context)
                && context.isPythonRepairPending()
                && !context.isPythonRepairExhausted()
                && currentAttempt(context) > 0
                && context.isResultConsumed();
    }

    @Override
    public String buildRepairInstruction(ToolJobResumeContext context) {
        String stage = promptService == null ? "" : promptService.pythonRepairStageInstruction();
        return "\n\n" + stage + buildRepairUserMessage(context);
    }

    static String buildRepairUserMessage(ToolJobResumeContext context) {
        StringBuilder out = new StringBuilder(512);
        out.append("\n\n[PYTHON_REPAIR_CONTEXT]\n")
                .append("repair_attempt: ").append(context.getPythonRepairAttempt()).append('\n')
                .append("terminal_status: ").append(safeRepairValue(context.getTerminalStatus())).append('\n')
                .append("exit_reason: ").append(safeRepairValue(context.getTerminalExitReason())).append('\n')
                .append("error_code: ").append(safeRepairValue(context.getTerminalErrorCode())).append('\n')
                .append("retryable: ").append(context.getTerminalRetryable()).append('\n')
                .append("stdout_preview:\n")
                .append(safeRepairBlock(context.getTerminalResultPreview())).append('\n')
                .append("stderr_preview:\n")
                .append(safeRepairBlock(context.getTerminalStderrPreview())).append('\n')
                .append("repair_instruction: 必须根据上述诊断生成修正后的新代码，禁止原样重放失败请求。\n")
                .append("failed_code_preview (untrusted):\n")
                .append(safeRepairBlock(context.getPythonFailedCodePreview())).append('\n');
        return out.toString();
    }

    @Override
    public int maxAttempts() {
        int startupValue = startupProperties == null
                ? DEFAULT_MAX_ATTEMPTS
                : startupProperties.getMaxAttempts();
        int configuredValue = configLoader == null
                ? startupValue
                : configLoader.current()
                        .map(CodeRefineProperties::getMaxAttempts)
                        .orElse(startupValue);
        if (configuredValue <= 0) {
            configuredValue = DEFAULT_MAX_ATTEMPTS;
        }
        return Math.min(configuredValue, MAX_ATTEMPTS_CAP);
    }

    @Override
    public int currentAttempt(ToolJobResumeContext context) {
        return context == null ? 0 : context.getPythonRepairAttempt();
    }

    @Override
    public void markPending(ToolJobResumeContext context, int repairAttempt) {
        context.setPythonRepairAttempt(repairAttempt);
        context.setPythonRepairPending(true);
        context.setPythonRepairExhausted(false);
    }

    @Override
    public void markExhausted(ToolJobResumeContext context) {
        context.setPythonRepairExhausted(true);
    }

    @Override
    public void clearPending(ToolJobResumeContext context) {
        context.setPythonRepairPending(false);
    }

    @Override
    public void activateRuntime(ToolJobResumeContext context) {
        AgentContext.setPythonRefineAttempt(currentAttempt(context));
        AgentContext.setPythonRepairContext(new PythonRepairContext(
                currentAttempt(context),
                context.getPythonFailedRequestFingerprints()));
    }

    @Override
    public void prepareSemanticRetryRuntime() {
        AgentContext.setPythonRefineAttempt(1);
        AgentContext.clearPythonRepairContext();
    }

    @Override
    public boolean acceptsExecution(dev.langchain4j.service.tool.ToolExecution execution) {
        if (execution == null
                || execution.request() == null
                || !toolName().equals(execution.request().name())
                || execution.hasFailed()
                || isBlank(execution.result())) {
            return false;
        }
        try {
            JsonNode root = TOOL_RESULT_MAPPER.readTree(execution.result());
            return root != null && root.path("ok").asBoolean(false);
        } catch (Exception malformedToolResult) {
            return false;
        }
    }

    @Override
    public String exhaustedFailureCode() {
        return EXHAUSTED_FAILURE_CODE;
    }

    @Override
    public String executeRequiredFailureCode() {
        return EXECUTE_REQUIRED_FAILURE_CODE;
    }

    @Override
    public Map<String, Object> exhaustedMetadata(ToolJobResumeContext context, int maxAttempts) {
        int failedCount = context == null || context.getPythonFailedRequestFingerprints() == null
                ? 0 : context.getPythonFailedRequestFingerprints().size();
        return Map.of(
                "python_repair_exhausted", true,
                "max_attempts", maxAttempts,
                "failed_request_count", failedCount);
    }

    @Override
    public Map<String, Object> repairingMetadata(ToolJobResumeContext context) {
        int failedCount = context.getPythonFailedRequestFingerprints() == null
                ? 0 : context.getPythonFailedRequestFingerprints().size();
        return Map.of(
                "python_repair_attempt", currentAttempt(context),
                "failed_request_count", failedCount,
                "exit_reason", nvl(context.getTerminalExitReason(), "unknown"));
    }

    @Override
    public Map<String, Object> executeRequiredMetadata(ToolJobResumeContext context) {
        return Map.of(
                "python_repair_postcondition_failed", true,
                "required_tool", toolName(),
                "repair_attempt", currentAttempt(context));
    }

    private static String nvl(String primary, String fallback) {
        if (primary == null || primary.trim().isEmpty()) {
            return fallback == null ? "" : fallback;
        }
        return primary;
    }

    private static String safeRepairValue(String value) {
        return value == null || value.isBlank() ? "(unavailable)" : value.trim();
    }

    private static String safeRepairBlock(String value) {
        return value == null || value.isBlank() ? "(unavailable)" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
