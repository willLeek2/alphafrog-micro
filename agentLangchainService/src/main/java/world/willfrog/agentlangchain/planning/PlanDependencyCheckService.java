package world.willfrog.agentlangchain.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.stereotype.Service;
import world.willfrog.agent.tools.dependency.ToolDependencyCatalog;
import world.willfrog.agent.tools.dependency.ToolDependencyInspector;
import world.willfrog.agent.tools.dependency.ToolDependencyInspector.Inspection;
import world.willfrog.agent.tools.registry.AgentToolRegistry;
import world.willfrog.agent.tools.registry.AgentToolRegistry.Dependency;
import world.willfrog.agent.workflow.PlanDependencyCheckReport;
import world.willfrog.agent.workflow.PlanDependencyCheckReport.Basis;
import world.willfrog.agent.workflow.PlanDependencyCheckReport.Hint;
import world.willfrog.agent.workflow.PlanDependencyCheckReport.Status;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.execution.LangchainWorkflowRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 有效计划形成后，汇总当前进程可取得的有限检查事实。 */
@Service
public class PlanDependencyCheckService {
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}\\p{N}._:@/\\-]{1,256}");
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(?:sk|ak|pk|rk|ghp|xox[baprs]|ya29)[_-][A-Za-z0-9._-]{12,}|AKIA[0-9A-Z]{16}");
    private final ToolDependencyInspector inspector;
    private final ObjectMapper objectMapper;

    public PlanDependencyCheckService(ToolDependencyInspector inspector, ObjectMapper objectMapper) {
        this.inspector = inspector;
        this.objectMapper = objectMapper;
    }

    public PlanDependencyCheckReport check(LangchainTodoPlan effectivePlan,
                                           LangchainWorkflowRequest request) {
        long started = System.nanoTime();
        Collector collector = new Collector(Instant.now().toString(),
                request == null ? null : request.getPlanGeneration());
        Set<String> allowed = new HashSet<>();
        if (request != null && request.getToolSpecifications() != null) {
            for (ToolSpecification specification : request.getToolSpecifications()) {
                if (specification != null) {
                    allowed.add(specification.name());
                }
            }
        }
        EnumMap<Dependency, Inspection> inspected = new EnumMap<>(Dependency.class);
        Inspection modelConfig = request != null && request.executionModelOrDefault() != null
                ? new Inspection(Status.AVAILABLE, Basis.CONFIG_PRESENT,
                        "当前进程已有执行阶段模型对象；模型业务请求未检查。")
                : ToolDependencyInspector.unchecked("没有可读的已组装执行模型；模型请求未检查。");
        inspected.put(Dependency.EXECUTION_MODEL, modelConfig);
        collector.add(new Hint("", "", Dependency.EXECUTION_MODEL.id(), Dependency.EXECUTION_MODEL.label(),
                modelConfig.status(), modelConfig.basis(), "所有步骤共用：" + modelConfig.reason()));
        List<TodoItem> items = effectivePlan == null || effectivePlan.getItems() == null
                ? List.of() : effectivePlan.getItems();
        for (TodoItem todo : items) {
            if (todo == null) {
                continue;
            }
            collector.currentTodoId = todo.getId();
            String todoId = safeIdentifier(todo.getId());
            List<String> expectedTools = todo.getExpectedTools() == null ? List.of() : todo.getExpectedTools();
            if (collector.full() || elapsedMs(started) >= PlanDependencyCheckReport.TRAVERSAL_BUDGET_MS) {
                collector.omitUnexpanded(todo.getId(), Math.max(1, expectedTools.size()));
                continue;
            }
            if (expectedTools.isEmpty()) {
                collector.add(new Hint(todoId, "", "plan.expected-tools", "预计工具",
                        Status.UNCHECKED, Basis.NONE, "本步骤没有明确预计工具；工具依赖未检查。"));
                continue;
            }
            Set<String> seen = new HashSet<>();
            for (int toolIndex = 0; toolIndex < expectedTools.size(); toolIndex++) {
                if (collector.full() || elapsedMs(started) >= PlanDependencyCheckReport.TRAVERSAL_BUDGET_MS) {
                    collector.omitUnexpanded(todo.getId(), expectedTools.size() - toolIndex);
                    break;
                }
                String expected = expectedTools.get(toolIndex);
                String name = expected == null ? "" : expected.trim();
                if (!seen.add(name)) {
                    continue;
                }
                String displayName = safeIdentifier(name);
                boolean registered = AgentToolRegistry.find(name).isPresent();
                if (!registered) {
                    collector.add(new Hint(todoId, displayName, "tool.unknown", "工具登记",
                            Status.UNCHECKED, Basis.NONE, "无法识别该工具名；依赖未检查。"));
                    continue;
                }
                if (!allowed.contains(name)) {
                    collector.add(new Hint(todoId, displayName, "tool.availability", "本运行工具开放情况",
                            Status.UNAVAILABLE, Basis.TOOL_AVAILABILITY, "工具已登记，但本次运行的实际工具集合未开放它。"));
                    continue;
                }
                collector.add(new Hint(todoId, displayName, "tool.availability", "本运行工具开放情况",
                        Status.AVAILABLE, Basis.TOOL_AVAILABILITY, "本次运行工具集合包含该工具；预计名单不限制后续工具选择。"));
                Set<Dependency> dependencies = ToolDependencyCatalog.resolve(name);
                if (dependencies.isEmpty()) {
                    collector.add(new Hint(todoId, displayName, "tool.dependencies", "工具依赖",
                            Status.UNCHECKED, Basis.NONE, "工具登记尚未提供检查依据；依赖未检查。"));
                }
                for (Dependency dependency : dependencies.stream().sorted(Comparator.comparing(Dependency::id)).toList()) {
                    Inspection result = inspected.get(dependency);
                    if (result == null) {
                        if (collector.full()) {
                            result = ToolDependencyInspector.unchecked("提示数量已达上限；未检查。");
                        } else if (elapsedMs(started) >= PlanDependencyCheckReport.TRAVERSAL_BUDGET_MS) {
                            result = ToolDependencyInspector.unchecked("本地遍历预算已用尽；未检查。");
                        } else if (inspected.size() >= PlanDependencyCheckReport.MAX_DEPENDENCIES) {
                            result = ToolDependencyInspector.unchecked("本轮依赖检查数量已达上限；未检查。");
                        } else {
                            try {
                                result = inspector == null ? ToolDependencyInspector.unchecked("检查组件不可用；未检查。")
                                        : inspector.inspect(dependency);
                            } catch (Exception ignored) {
                                result = ToolDependencyInspector.unchecked("读取检查依据时发生异常；未检查。");
                            }
                            inspected.put(dependency, result);
                        }
                    }
                    collector.add(new Hint(todoId, displayName, dependency.id(), dependency.label(),
                            result.status(), result.basis(), result.reason()));
                }
                collector.add(new Hint(todoId, displayName, "tool.business", "实际业务调用",
                        Status.UNCHECKED, Basis.NONE, "预检没有调用业务方法；实际业务成败以随后该次工具结果为准。"));
                collector.add(new Hint(todoId, displayName, "tool.data-coverage", "数据覆盖",
                        Status.UNCHECKED, Basis.NONE, "没有本次对象、日期及字段的覆盖证据；数据覆盖未检查。"));
            }
        }
        collector.checkedDependencyCount = inspected.size();
        collector.durationMs = elapsedMs(started);
        return collector.boundedReport();
    }

    private static long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    /** 身份通过安全检查时完整保留原值；无法表示时留空，避免伪造相同身份。 */
    public static String safeIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches() && !value.contains("://")
                && !CREDENTIAL.matcher(value).matches() ? value : "";
    }

    private final class Collector {
        private final String checkedAt;
        private final Integer generation;
        private final List<Hint> hints = new ArrayList<>();
        private final Set<String> omittedTodos = new LinkedHashSet<>();
        // 原身份只在内部用于计数；无法安全输出的不同步骤仍分别计入总影响数。
        private final Map<Hint, String> originalTodoIds = new IdentityHashMap<>();
        private String currentTodoId;
        private int omittedTodoIdLimit = PlanDependencyCheckReport.MAX_HINTS;
        private int omittedCount;
        private int checkedDependencyCount;
        private long durationMs;

        private Collector(String checkedAt, Integer generation) {
            this.checkedAt = checkedAt;
            this.generation = generation;
        }

        private boolean full() {
            return hints.size() >= PlanDependencyCheckReport.MAX_HINTS;
        }

        private void add(Hint hint) {
            if (full()) {
                recordOmission(currentTodoId);
            } else {
                hints.add(hint);
                originalTodoIds.put(hint, currentTodoId);
            }
        }

        private void omit(Hint hint) {
            recordOmission(originalTodoIds.remove(hint));
        }

        private void recordOmission(String originalTodoId) {
            omittedCount++;
            if (originalTodoId != null && !originalTodoId.isBlank()) {
                omittedTodos.add(originalTodoId);
            }
        }

        private void omitUnexpanded(String todoId, int count) {
            // 未遍历的预计工具记录各对应一条待检查提示，只取数组长度，不再展开工具或依赖。
            omittedCount += count;
            if (todoId != null && !todoId.isBlank()) {
                omittedTodos.add(todoId);
            }
        }

        private PlanDependencyCheckReport report() {
            List<Hint> visible = new ArrayList<>(hints);
            if (omittedCount > 0) {
                visible.add(new Hint("", "", "report.omitted", "未覆盖部分", Status.UNCHECKED, Basis.NONE,
                        "数量、时间或字符预算已用尽；报告未覆盖省略项，不能据此判断可用性，数量和影响步骤见省略信息。"));
            }
            return PlanDependencyCheckReport.builder()
                    .checkedAt(checkedAt).checkedPlanGeneration(generation)
                    .durationMs(durationMs).checkedDependencyCount(checkedDependencyCount)
                    .hints(List.copyOf(visible)).omittedCount(omittedCount)
                    .omittedTodoIds(omittedTodos.stream().map(PlanDependencyCheckService::safeIdentifier)
                            .filter(id -> !id.isBlank()).limit(omittedTodoIdLimit).toList())
                    .omittedTodoCount(omittedTodos.size()).truncated(omittedCount > 0).build();
        }

        private PlanDependencyCheckReport boundedReport() {
            if (omittedCount > 0 && full()) {
                omit(hints.remove(hints.size() - 1));
            }
            PlanDependencyCheckReport result = report();
            while (!hints.isEmpty() && jsonLength(result) > PlanDependencyCheckReport.MAX_REPORT_CHARACTERS) {
                omit(hints.remove(hints.size() - 1));
                result = report();
            }
            while (!result.getOmittedTodoIds().isEmpty()
                    && jsonLength(result) > PlanDependencyCheckReport.MAX_REPORT_CHARACTERS) {
                omittedTodoIdLimit = result.getOmittedTodoIds().size() - 1;
                result = report();
            }
            return result;
        }

        private int jsonLength(PlanDependencyCheckReport report) {
            try {
                return objectMapper.writeValueAsString(report).length();
            } catch (Exception ignored) {
                // 报告只含普通数据；序列化异常时缩减为省略说明，不暴露异常正文。
                return Integer.MAX_VALUE;
            }
        }
    }
}
