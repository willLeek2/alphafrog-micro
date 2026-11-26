package world.willfrog.agentlangchain.facade;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agentlangchain.tooljob.ToolResultRecordedEventPublisher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 从已有事件生成返回给页面的结果视图，不修改保存的计划或检查报告。 */
final class PlanDependencyExecutionView {

    private static final int MAX_RESULTS = 32;
    private static final int MAX_VIEW_CHARACTERS = 8000;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final Set<String> EVENT_TYPES = Set.of(
            "WORKFLOW_RESUMED", "WORKFLOW_RESTARTED", "DEPENDENCIES_CHECKED", "TOOL_CALL_STARTED", "TOOL_CALL_FINISHED",
            ToolResultRecordedEventPublisher.EVENT_TYPE);

    private PlanDependencyExecutionView() {
    }

    static String enrich(String planJson, AgentRun run, List<AgentRunEvent> events, ObjectMapper mapper) {
        if (planJson == null || planJson.isBlank() || run == null) {
            return planJson;
        }
        try {
            JsonNode parsed = mapper.readTree(planJson);
            if (!(parsed instanceof ObjectNode plan)) {
                return planJson;
            }
            plan.set("dependencyExecution", mapper.valueToTree(build(run, events, mapper)));
            return mapper.writeValueAsString(plan);
        } catch (Exception ignored) {
            // 读取增强失败时保留原计划；不能改变运行状态或把未知结果显示为成功。
            return planJson;
        }
    }

    private static Map<String, Object> build(AgentRun run, List<AgentRunEvent> events,
                                             ObjectMapper mapper) throws Exception {
        List<EventPayload> ordered = orderedEvents(run, events, mapper);
        Integer currentGeneration = run.getPlanGeneration();
        int resumeSeq = 0;
        int restartSeq = 0;
        for (EventPayload event : ordered) {
            if ("WORKFLOW_RESUMED".equals(event.type()) || "WORKFLOW_RESTARTED".equals(event.type())) {
                resumeSeq = event.seq();
            }
            if ("WORKFLOW_RESTARTED".equals(event.type())) {
                restartSeq = event.seq();
            }
        }
        EventPayload check = null;
        int resultAfterSeq = restartSeq;
        for (EventPayload event : ordered) {
            if ("DEPENDENCIES_CHECKED".equals(event.type()) && event.seq() > restartSeq
                    && sameGeneration(event.payload(), currentGeneration)) {
                if (check == null) {
                    // 长工具先保存结果再恢复，后发的复用报告不能排除这次调用的原结果。
                    resultAfterSeq = event.seq();
                }
                check = event;
            }
        }
        Map<String, Object> view = new LinkedHashMap<>();
        if (currentGeneration != null) {
            view.put("plan_generation", currentGeneration);
        }
        if (resumeSeq > 0) {
            view.put("resume_event_seq", resumeSeq);
        }
        // 报告通知可在同次调用恢复后更新；只有重新执行及其首次检查推进结果下界。
        view.put("result_after_seq", resultAfterSeq);
        view.put("requires_start_proof", restartSeq > 0);
        view.put("prepared", check != null);
        view.put("frozen_reuse", check != null && Boolean.TRUE.equals(check.payload().get("frozen_reuse")));
        if (check == null) {
            view.put("results", List.of());
            view.put("call_starts", List.of());
            view.put("omitted_call_start_count", 0);
            view.put("omitted_count", 0);
            view.put("omitted_todo_ids", List.of());
            return view;
        }
        view.put("check_event_seq", check.seq());
        Map<StartKey, Integer> starts = new HashMap<>();
        Map<CallKey, Map<String, Object>> results = new LinkedHashMap<>();
        for (EventPayload event : ordered) {
            if (event.seq() <= resultAfterSeq) {
                continue;
            }
            if ("TOOL_CALL_STARTED".equals(event.type())) {
                if (!event.payload().containsKey("plan_generation")
                        || sameGeneration(event.payload(), currentGeneration)) {
                    StartKey key = startKey(ToolResultRecordedEventPublisher.safeResult(event.payload()));
                    if (key != null) {
                        starts.put(key, event.seq());
                    }
                }
                continue;
            }
            boolean synchronous = "TOOL_CALL_FINISHED".equals(event.type());
            if (!synchronous && !ToolResultRecordedEventPublisher.EVENT_TYPE.equals(event.type())) {
                continue;
            }
            Map<String, Object> raw = new LinkedHashMap<>(event.payload());
            if (synchronous) {
                raw.put("source", "synchronous");
                raw.remove("business_success");
                // 旧后台事件的 success 只说明任务进程终态，业务结论由独立结果通知提供。
                boolean legacyTask = raw.get("operation_id") != null
                        || raw.get("task_id") != null || raw.get("status") != null;
                if (legacyTask && raw.get("task_status") == null) {
                    raw.put("task_status", raw.get("status"));
                }
                if (!legacyTask && event.payload().get("success") instanceof Boolean success) {
                    raw.put("business_success", success);
                }
            }
            Map<String, Object> result = ToolResultRecordedEventPublisher.safeResult(raw);
            StartKey identity = startKey(result);
            if (identity == null || !result.containsKey("source")) {
                continue;
            }
            if (!synchronous && !"wait_member".equals(result.get("source"))
                    && !"tool_job_anchor".equals(result.get("source"))) {
                continue;
            }
            if ("wait_member".equals(result.get("source"))
                    && !"SUCCEEDED".equals(result.get("member_state"))
                    && !"FAILED".equals(result.get("member_state"))) {
                continue;
            }
            if (restartSeq > 0 && !starts.containsKey(identity)) {
                // 重启可能不增加计划代次；晚到的旧调用结果不能仅凭同代次进入新一轮。
                continue;
            }
            if (!sameGeneration(result, currentGeneration)) {
                // 旧同步完成事件须有同轮、同调用身份的开始事实；归属来自检查事件而非补读 Run。
                if (!synchronous || event.payload().get("plan_generation") != null
                        || !starts.containsKey(identity)) {
                    continue;
                }
                result.put("plan_generation", ((Number) check.payload().get("plan_generation")).longValue());
            }
            CallKey key = new CallKey(currentGeneration, identity.todo(), identity.tool(), identity.call());
            Map<String, Object> previous = results.get(key);
            if (previous != null && previous.get("business_success") instanceof Boolean
                    && (!(result.get("business_success") instanceof Boolean)
                    || (!"synchronous".equals(previous.get("source")) && synchronous))) {
                // 最终保存的成员/锚点结果优先于同步通知，未知结果也不能覆盖明确业务结论。
                continue;
            }
            result.put("event_seq", event.seq());
            results.put(key, result);
        }
        List<Map<String, Object>> all = new ArrayList<>(results.values());
        all.sort(Comparator.comparingInt(PlanDependencyExecutionView::eventSeq).reversed());
        List<Map<String, Object>> selected = new ArrayList<>(all.subList(0, Math.min(MAX_RESULTS, all.size())));
        List<Map<String, Object>> allStarts = new ArrayList<>();
        for (Map.Entry<StartKey, Integer> entry : starts.entrySet()) {
            StartKey identity = entry.getKey();
            if (results.containsKey(new CallKey(currentGeneration, identity.todo(), identity.tool(), identity.call()))) {
                continue;
            }
            Map<String, Object> start = new LinkedHashMap<>();
            start.put("event_seq", entry.getValue());
            start.put("plan_generation", currentGeneration);
            start.put("todo_id", identity.todo());
            start.put("tool_name", identity.tool());
            start.put("tool_call_id", identity.call());
            allStarts.add(start);
        }
        allStarts.sort(Comparator.comparingInt(PlanDependencyExecutionView::eventSeq).reversed());
        List<Map<String, Object>> selectedStarts = new ArrayList<>(allStarts.subList(0, Math.min(MAX_RESULTS, allStarts.size())));
        fillResults(view, all, selected);
        fillStarts(view, allStarts, selectedStarts);
        while (mapper.writeValueAsString(view).length() > MAX_VIEW_CHARACTERS
                && (!selected.isEmpty() || !selectedStarts.isEmpty())) {
            // 完成结果和进行中身份共享响应体积预算，优先保留较近的事实。
            if (!selectedStarts.isEmpty() && (selected.isEmpty()
                    || eventSeq(selectedStarts.get(selectedStarts.size() - 1))
                    < eventSeq(selected.get(selected.size() - 1)))) {
                selectedStarts.remove(selectedStarts.size() - 1);
            } else {
                selected.remove(selected.size() - 1);
            }
            fillResults(view, all, selected);
            fillStarts(view, allStarts, selectedStarts);
        }
        @SuppressWarnings("unchecked")
        List<String> omittedTodos = (List<String>) view.get("omitted_todo_ids");
        while (mapper.writeValueAsString(view).length() > MAX_VIEW_CHARACTERS && !omittedTodos.isEmpty()) {
            omittedTodos.remove(omittedTodos.size() - 1);
        }
        return view;
    }

    private static void fillStarts(Map<String, Object> view, List<Map<String, Object>> all,
                                   List<Map<String, Object>> selected) {
        view.put("call_starts", selected);
        view.put("omitted_call_start_count", all.size() - selected.size());
    }

    private static void fillResults(Map<String, Object> view, List<Map<String, Object>> all,
                                    List<Map<String, Object>> selected) {
        view.put("results", selected);
        view.put("omitted_count", all.size() - selected.size());
        Set<String> omitted = new LinkedHashSet<>();
        for (int index = selected.size(); index < all.size() && omitted.size() < MAX_RESULTS; index++) {
            Object todo = all.get(index).get("todo_id");
            if (todo instanceof String id) {
                omitted.add(id);
            }
        }
        view.put("omitted_todo_ids", new ArrayList<>(omitted));
    }

    private static List<EventPayload> orderedEvents(AgentRun run, List<AgentRunEvent> events,
                                                    ObjectMapper mapper) {
        List<EventPayload> ordered = new ArrayList<>();
        for (AgentRunEvent event : events == null ? List.<AgentRunEvent>of() : events) {
            if (event == null || event.getSeq() == null || event.getSeq() <= 0
                    || event.getEventType() == null || !EVENT_TYPES.contains(event.getEventType())
                    || run.getId() == null || !run.getId().equals(event.getRunId())) {
                continue;
            }
            Map<String, Object> payload = Map.of();
            try {
                JsonNode node = mapper.readTree(event.getPayloadJson() == null ? "" : event.getPayloadJson());
                if (node != null && node.isObject()) {
                    payload = mapper.convertValue(node, MAP_TYPE);
                }
            } catch (Exception ignored) {
                // 畸形载荷只保留事件边界，不推断它的业务结果。
            }
            ordered.add(new EventPayload(event.getSeq(), event.getEventType(), payload));
        }
        ordered.sort(Comparator.comparingInt(EventPayload::seq));
        return ordered;
    }

    private static boolean sameGeneration(Map<String, ?> payload, Integer generation) {
        return generation != null && generation >= 0
                && payload.get("plan_generation") instanceof Number number
                && number.longValue() == generation.longValue()
                && number.doubleValue() == generation.doubleValue();
    }

    private static StartKey startKey(Map<String, Object> payload) {
        if (payload.get("todo_id") instanceof String todo
                && payload.get("tool_name") instanceof String tool
                && payload.get("tool_call_id") instanceof String call) {
            return new StartKey(todo, tool, call);
        }
        return null;
    }

    private static int eventSeq(Map<String, Object> result) {
        return ((Number) result.get("event_seq")).intValue();
    }

    private record StartKey(String todo, String tool, String call) { }

    private record CallKey(int generation, String todo, String tool, String call) { }

    private record EventPayload(int seq, String type, Map<String, Object> payload) { }
}
