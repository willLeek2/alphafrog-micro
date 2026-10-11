package world.willfrog.agent.platform.observability;

import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 结构化可观测事件（合同里的「日志事件」切面）的统一上报入口。
 *
 * <p>业务代码只在决策点调用本入口：事件按合同字段组装成一条 JSON 日志行，落到
 * {@code /app/logs/app.log}，由日志管道采集转发。不新增表、不新增网络出口、不加锁、不等待，
 * 也不改任何业务状态。</p>
 *
 * <h2>使用约束</h2>
 * <ul>
 *   <li>开关：走热配置（与双池调度参数同一路读取方式，见 {@link ObservabilityEventsSwitch}），
 *       代码默认关。关闭时 {@code emit()} 直接返回，行为与没有这段代码完全一致；开启判断在拼字段之前。</li>
 *   <li>身份：运行编号在日志上下文里没有，必须由调用方当参数传入；轨迹编号和轨迹片段编号只取
 *       OpenTelemetry 代理注入的日志上下文（{@code trace_id} / {@code span_id}），没有就不带。</li>
 *   <li>安全：入口吞掉自身异常——上报通道的问题不允许抛回业务调用方。</li>
 * </ul>
 *
 * <h2>事件名</h2>
 * <p>只上报业务记录里没有发生时刻的决策点事实（准入、领取、恢复判定、停止确认）。其余事件名
 * 由读取侧从既有业务记录只读派生，不在这里上报。</p>
 */
public final class ObservabilityEvents {

    /** 事件日志专用 logger 名，便于在日志里按来源过滤。 */
    private static final Logger EVENT_LOG = LoggerFactory.getLogger("observability.events");

    /** 合同约定的默认时区：东八区（UTC+8），时刻写成带偏移的形式。 */
    private static final ZoneOffset DEFAULT_ZONE = ZoneOffset.ofHours(8);

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** 详情这一批不做：统一按「未采集」标注。 */
    private static final String DETAIL_STATUS_NOT_COLLECTED = "not_collected";

    /** 默认事件级别。 */
    private static final String DEFAULT_SEVERITY = "info";

    /** 合同事件名（与读取侧枚举里的名字一致）。 */
    public static final String WORK_CLAIMED = "work_claimed";
    public static final String ADMISSION_DECIDED = "admission_decided";
    public static final String RECOVERY_DECIDED = "recovery_decided";
    public static final String EXTERNAL_STOP_CHECKED = "external_stop_checked";

    /** 开关探针：由 {@link ObservabilityEventsSwitch} 在启动时装配；没有装配时按关闭处理。 */
    private static volatile BooleanSupplier enabledProbe;

    private ObservabilityEvents() {
    }

    /** 装配开关探针；没有 Spring 上下文时（单测或未扫到本包的服务）保持关闭。 */
    static void installSwitch(BooleanSupplier probe) {
        enabledProbe = probe;
    }

    /** 每次上报都重新取一次开关值，泳道改完热配置下一轮上报就生效。 */
    static boolean enabled() {
        BooleanSupplier probe = enabledProbe;
        return probe != null && probe.getAsBoolean();
    }

    /** 工作项被某个执行者领走。 */
    public static Event workClaimed() {
        return new Event(WORK_CLAIMED);
    }

    /** 运行准入判定做出（允许与拒绝都报）。 */
    public static Event admissionDecided() {
        return new Event(ADMISSION_DECIDED);
    }

    /** 等待组的恢复判定做出（消费、延期、关闭都算一次决定）。 */
    public static Event recoveryDecided() {
        return new Event(RECOVERY_DECIDED);
    }

    /** 外部停止被实际确认成立。 */
    public static Event externalStopChecked() {
        return new Event(EXTERNAL_STOP_CHECKED);
    }

    /**
     * 一条事件的字段组装。字段名与合同「日志事件」切面的 {@code events[]} 一致；可空字段不传时
     * 不出现在日志行里。每次上报新建一个实例、不回传、不共享，所以不需要考虑并发。
     */
    public static final class Event {

        private final String eventName;
        private String severity = DEFAULT_SEVERITY;
        private String rootRunId;
        private String nodeId;
        private Integer segmentSequence;
        private String operationId;
        private String decision;
        private String outcome;
        private String reasonCode;
        private String summary;

        private Event(String eventName) {
            this.eventName = eventName;
        }

        /** 事件所属根运行编号；必须由调用方传入（日志上下文里没有）。 */
        public Event rootRunId(String rootRunId) {
            this.rootRunId = rootRunId;
            return this;
        }

        /** 事件所属节点编号；运行级事件不传。 */
        public Event nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        /** 事件所属执行段顺序号；运行级或节点级事件可以不传。 */
        public Event segmentSequence(Integer segmentSequence) {
            this.segmentSequence = segmentSequence;
            return this;
        }

        /** 工具调用、恢复尝试或外部停止等操作编号。 */
        public Event operationId(String operationId) {
            this.operationId = operationId;
            return this;
        }

        /** 事件记录的决定，例如 {@code admitted}、{@code granted}、{@code deferred}、{@code discarded}。 */
        public Event decision(String decision) {
            this.decision = decision;
            return this;
        }

        /** 事件处理结果，例如 {@code pending}、{@code success}、{@code failure}。 */
        public Event outcome(String outcome) {
            this.outcome = outcome;
            return this;
        }

        /** 决定或结果的稳定原因编号。 */
        public Event reasonCode(String reasonCode) {
            this.reasonCode = reasonCode;
            return this;
        }

        /** 事件级别：{@code debug}、{@code info}、{@code warn}、{@code error}；不传为 {@code info}。 */
        public Event severity(String severity) {
            this.severity = severity;
            return this;
        }

        /** 不包含原始请求、响应或授权信息的简短说明。 */
        public Event summary(String summary) {
            this.summary = summary;
            return this;
        }

        /** 上报一条事件；关闭时直接返回，自身出任何问题都不抛回调用方。 */
        public void emit() {
            try {
                if (!ObservabilityEvents.enabled()) {
                    return;
                }
                String text = summary == null || summary.isBlank() ? eventName : summary;
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("eventId", UUID.randomUUID().toString());
                fields.put("eventName", eventName);
                fields.put("severity", severity == null || severity.isBlank() ? DEFAULT_SEVERITY : severity);
                fields.put("occurredAt", OffsetDateTime.now(DEFAULT_ZONE).format(TIME_FORMAT));
                putText(fields, "rootRunId", rootRunId);
                putText(fields, "nodeId", nodeId);
                if (segmentSequence != null) {
                    fields.put("segmentSequence", segmentSequence);
                }
                putText(fields, "operationId", operationId);
                putText(fields, "decision", decision);
                putText(fields, "outcome", outcome);
                putText(fields, "reasonCode", reasonCode);
                putText(fields, "traceId", MDC.get("trace_id"));
                putText(fields, "spanId", MDC.get("span_id"));
                fields.put("summary", text);
                fields.put("detailStatus", DETAIL_STATUS_NOT_COLLECTED);
                EVENT_LOG.info(text, StructuredArguments.entries(fields));
            } catch (Throwable ignored) {
                // 上报通道自身的问题不允许影响业务：静默返回，不记录、不抛出。
            }
        }

        private static void putText(Map<String, Object> fields, String key, String value) {
            if (value != null && !value.isBlank()) {
                fields.put(key, value);
            }
        }
    }
}
