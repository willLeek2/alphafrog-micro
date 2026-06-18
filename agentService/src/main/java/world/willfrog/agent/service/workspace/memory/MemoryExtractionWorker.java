package world.willfrog.agent.service.workspace.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.service.workspace.WorkspacePathResolver;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * simple memory 抽取 worker。
 *
 * <p>v0 只支持从 workspace dump 输出（meta.json + conversation.jsonl）抽取少量可验证条目：
 * <ul>
 *   <li>用户明确表达的偏好（"输出语言：中文"、"代码风格：..."）</li>
 *   <li>仍未解决的问题（"TODO: ..."、"还没实现 ..."）</li>
 *   <li>已经被用户确认的纠错（"刚才那个实现不对，应该是 ..."）</li>
 *   <li>项目事实（"项目当前采用 ..."）</li>
 * </ul>
 *
 * <p>不推断模糊偏好；每条 memory 带 source run + message seq 方便追溯 / supersede。
 *
 * @author wang
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MemoryExtractionWorker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** "输出语言：中文" / "代码风格：..." 等偏好 */
    private static final Pattern PREFERENCE_PATTERN = Pattern.compile(
            "(输出语言|代码风格|输出格式|默认)[：:]\\s*([^\\n。]+)"
    );
    /** "TODO:" / "还没实现" / "缺" / "待补" 标记 open issue */
    private static final Pattern OPEN_ISSUE_PATTERN = Pattern.compile(
            "(TODO[:：]|还没实现|尚未实现|待补|待实现)"
    );
    /** "刚才/之前/以前 ... 错了/不对" 标记 correction */
    private static final Pattern CORRECTION_PATTERN = Pattern.compile(
            "(刚才|之前|以前|上次)[^\\n。]{0,40}(错了|不对|不要再|不要用)"
    );
    /** "项目/代码/系统 当前/现在 采用/使用/是 ..." */
    private static final Pattern FACT_PATTERN = Pattern.compile(
            "(项目|代码|系统|实现|架构)[^\\n。]{0,10}(当前|现在)[^\\n。]{0,30}(采用|使用|是|为)"
    );

    private final AgentConversationMemoryMapper memoryMapper;
    private final WorkspacePathResolver pathResolver;

    @Value("${agent.workspace.root:/data/agent_workspaces}")
    private String workspaceRoot;

    /**
     * 定时扫描最近 24h 已 dump 的 run，抽取 memory。
     *
     * <p>默认每小时跑一次；可通过 agent.workspace.memory.extraction.cron 覆盖。
     * v0 只暴露按 runId 抽取的入口，cron 任务本身不做时间窗口扫描。
     */
    @Scheduled(cron = "${agent.workspace.memory.extraction.cron:0 0 * * * *}")
    public void scheduledExtract() {
        log.debug("MemoryExtractionWorker.scheduledExtract running");
    }

    /**
     * 从指定 run 的 workspace dump 输出中抽取 memory。
     *
     * <p>读 conversation.jsonl，按消息角色 + 关键词匹配写入 memory 表。v0 不去重、不 supersede，
     * 由上层 review 工具处理。
     *
     * @param runId    run 主键
     * @param userId   用户 id（数值）
     * @param tenantId 租户 id
     * @return 抽取并写入的 memory 数量
     */
    public int extractFromRun(String runId, long userId, String tenantId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        Path runDir;
        try {
            runDir = pathResolver.resolveRunDir(runId, userId, "");
        } catch (Exception e) {
            log.warn("extractFromRun skipped: resolveRunDir failed runId={} userId={}", runId, userId, e);
            return 0;
        }
        Path conversation = runDir.resolve("conversation.jsonl");
        if (!Files.exists(conversation)) {
            log.warn("extractFromRun: conversation.jsonl 不存在 runId={}", runId);
            return 0;
        }
        int count = 0;
        try {
            List<String> lines = Files.readAllLines(conversation);
            int seqStart = 0;
            int seqEnd = 0;
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode msg = MAPPER.readTree(line);
                String role = msg.path("role").asText("");
                String content = msg.path("content").asText("");
                int seq = msg.path("seq").asInt(0);
                seqStart = (seqStart == 0) ? seq : Math.min(seqStart, seq);
                seqEnd = Math.max(seqEnd, seq);
                if (!"user".equals(role) && !"assistant".equals(role)) {
                    continue;
                }
                count += extractAndInsert(runId, userId, tenantId, role, content, seq, seq);
            }
        } catch (Exception e) {
            log.error("extractFromRun failed: runId={}", runId, e);
        }
        log.info("extractFromRun: runId={} extracted {} memory items", runId, count);
        return count;
    }

    private int extractAndInsert(String runId, long userId, String tenantId,
                                 String role, String content, int seqStart, int seqEnd) {
        int count = 0;
        count += tryInsert(runId, userId, tenantId, "preference", content, seqStart, seqEnd, PREFERENCE_PATTERN);
        count += tryInsert(runId, userId, tenantId, "open_issue", content, seqStart, seqEnd, OPEN_ISSUE_PATTERN);
        count += tryInsert(runId, userId, tenantId, "correction", content, seqStart, seqEnd, CORRECTION_PATTERN);
        count += tryInsert(runId, userId, tenantId, "fact", content, seqStart, seqEnd, FACT_PATTERN);
        return count;
    }

    private int tryInsert(String runId, long userId, String tenantId,
                          String memoryType, String content, int seqStart, int seqEnd, Pattern pattern) {
        Matcher m = pattern.matcher(content);
        int n = 0;
        while (m.find()) {
            String matched = m.group();
            if (matched.length() < 4) {
                continue;
            }
            AgentConversationMemory memory = new AgentConversationMemory();
            memory.setTenantId(tenantId);
            memory.setUserId(String.valueOf(userId));
            memory.setConversationScope(tenantId + "_" + userId);
            memory.setMemoryType(memoryType);
            memory.setContent(matched);
            memory.setSourceRunId(runId);
            memory.setSourceMessageSeqStart(seqStart);
            memory.setSourceMessageSeqEnd(seqEnd);
            memory.setConfidence(new BigDecimal("1.000"));
            memory.setVerificationStatus("auto_extracted");
            memory.setStatus("active");
            memory.setEmbeddingStatus("pending");
            try {
                memoryMapper.insert(memory);
                n++;
            } catch (Exception e) {
                log.warn("insert memory failed: runId={} type={}", runId, memoryType, e);
            }
        }
        return n;
    }
}
