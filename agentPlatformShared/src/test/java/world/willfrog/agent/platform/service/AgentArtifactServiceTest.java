package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.artifact.PersistentArtifactMeta;
import world.willfrog.agent.platform.artifact.PersistentArtifactRegistration;
import world.willfrog.agent.platform.artifact.PersistentArtifactRegistry;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.mapper.AgentRunEventMapper;
import world.willfrog.agent.platform.storage.AgentStoragePaths;
import world.willfrog.alphafrogmicro.agent.idl.AgentArtifactMessage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D22-5.1.3：AgentArtifactService 降级为 user API 门面 + legacy 适配器后的行为钉住。
 *
 * <ul>
 *   <li>①list 走 registry：已注册制品映射到原 DTO（字段零漂移）</li>
 *   <li>②惰性幂等：事件派生制品首次 list 注册，重复 list 同 ID、零新文件、run 索引不膨胀</li>
 *   <li>③load Registry-first：registry 命中直读，不触碰 legacy 解析</li>
 *   <li>④Base64 回退只读：旧位置可读，回退后 registry 仍无该 ID（不得注册）</li>
 *   <li>⑤跨 run 拒绝沿用 IllegalArgumentException("artifact not found")</li>
 *   <li>⑥@Value 存储键摘除：构造器不再持有 datasetPath/artifactStoragePath 字段，
 *       根路径一律经 AgentStoragePaths 注入（键面 grep 另证）</li>
 *   <li>⑦retention 两档 + download max-bytes 保护保持</li>
 * </ul>
 *
 * <p>Redis 用内存 fake（values/hashes/sets 三张表），registry 为真实
 * {@link PersistentArtifactRegistry} 实例，文件落 @TempDir；不起 Spring 上下文。
 * mock 风格与 {@code PersistentArtifactRegistryTest} / {@code ToolOutputRefServiceImplTest}
 * 一致：不起 MockitoExtension，纯 {@code mock()} 手工装配。</p>
 */
class AgentArtifactServiceTest {

    private static final String RUN_LIST_PREFIX = "agent:persistent-artifact:run-list:";
    private static final String META_PREFIX = "agent:persistent-artifact:";

    private AgentEventService eventService;
    private AgentRunEventMapper agentRunEventMapper;

    private AgentArtifactService service;
    private PersistentArtifactRegistry registry;

    @TempDir
    Path tempDir;

    private Map<String, String> values;
    private Map<String, Map<String, String>> hashes;
    private Map<String, Set<String>> sets;
    private Path artifactRoot;
    private Path datasetRoot;

    @BeforeEach
    void setUp() {
        eventService = mock(AgentEventService.class);
        agentRunEventMapper = mock(AgentRunEventMapper.class);
        values = new LinkedHashMap<>();
        hashes = new LinkedHashMap<>();
        sets = new LinkedHashMap<>();
        artifactRoot = tempDir.resolve("artifacts");
        datasetRoot = tempDir.resolve("datasets");
        AgentStoragePaths storagePaths = new AgentStoragePaths(
                tempDir.resolve("workspaces").toString(),
                artifactRoot.toString(),
                datasetRoot.toString(),
                tempDir.resolve("obs-debug.log").toString());
        registry = new PersistentArtifactRegistry(mockRedis(), new ObjectMapper(), storagePaths);
        ReflectionTestUtils.setField(registry, "defaultTtlHours", 12L);
        ReflectionTestUtils.setField(registry, "cleanupScanCount", 100);
        ReflectionTestUtils.setField(registry, "maxRunListEntries", 1000);

        service = new AgentArtifactService(eventService, agentRunEventMapper, new ObjectMapper(),
                registry, storagePaths);
        ReflectionTestUtils.setField(service, "normalRetentionDays", 7);
        ReflectionTestUtils.setField(service, "adminRetentionDays", 30);
        ReflectionTestUtils.setField(service, "downloadMaxBytes", 1024L * 1024L);
    }

    // ===== ① list 走 registry：已注册制品映射 DTO =====

    @Test
    void listArtifacts_shouldMapRegistryRegisteredArtifactsToDto() throws Exception {
        PersistentArtifactRegistration content = registry.registerExplicit(
                "run-1", "u1", "report", "rep-1", "report.txt", "hello", 6);
        Path externalFile = Files.createDirectories(datasetRoot.resolve("ds-x"))
                .resolve("data.csv");
        Files.writeString(externalFile, "a\n1\n");
        PersistentArtifactRegistration external = registry.registerExternalExplicit(
                "run-1", "u1", "dataset", "ds-x", "data.csv", externalFile, 6, false);
        when(eventService.listByRunId("run-1")).thenReturn(List.of());

        List<AgentArtifactMessage> artifacts = service.listArtifacts(run("run-1", "u1"), false);

        assertEquals(2, artifacts.size());
        AgentArtifactMessage report = findById(artifacts, content.getArtifactId());
        assertEquals("report", report.getType());
        assertEquals("report.txt", report.getName());
        assertEquals("application/octet-stream", report.getContentType());
        assertEquals("/api/agent/runs/run-1/artifacts/" + content.getArtifactId() + "/download",
                report.getUrl());
        assertTrue(report.getMetaJson().contains("\"kind\":\"report\""));
        assertTrue(report.getMetaJson().contains("\"scope\":\"normal\""));
        assertFalse(report.getCreatedAt().isBlank());
        assertTrue(report.getExpiresAtMillis() > System.currentTimeMillis());

        AgentArtifactMessage dataset = findById(artifacts, external.getArtifactId());
        assertEquals("dataset", dataset.getType());
        assertEquals("data.csv", dataset.getName());
        assertEquals("/api/agent/runs/run-1/artifacts/" + external.getArtifactId() + "/download",
                dataset.getUrl());
    }

    // ===== ② 惰性幂等注册（含原 TODO 事件流 DTO 契约） =====

    @Test
    void listArtifacts_shouldLazyRegisterEventDerivedArtifactsIdempotently() throws Exception {
        Path datasetDir = datasetRoot.resolve("ds1");
        Files.createDirectories(datasetDir);
        Files.writeString(datasetDir.resolve("ds1.csv"), "a,b\n1,2\n");
        Files.writeString(datasetDir.resolve("ds1.json"), "{\"columns\":[\"a\"],\"rows\":[[1]]}");
        Files.writeString(datasetDir.resolve("ds1.meta.json"), "{\"id\":\"ds1\"}");

        OffsetDateTime eventTime = OffsetDateTime.now(ZoneOffset.UTC);
        when(eventService.listByRunId("run-1")).thenReturn(todoEvents("run-1", eventTime));

        AgentRun run = run("run-1", "u1");
        List<AgentArtifactMessage> first = service.listArtifacts(run, false);

        // DTO 契约（零漂移）：type/name/url/metaJson/retention
        assertTrue(first.stream().anyMatch(a -> "python_script".equals(a.getType())));
        assertTrue(first.stream().anyMatch(a -> "dataset_csv".equals(a.getType()) && "ds1.csv".equals(a.getName())));
        assertTrue(first.stream().anyMatch(a -> "dataset_json".equals(a.getType()) && "ds1.json".equals(a.getName())));
        assertTrue(first.stream().anyMatch(a -> "dataset_meta".equals(a.getType()) && "ds1.meta.json".equals(a.getName())));
        AgentArtifactMessage script = first.stream()
                .filter(a -> "python_script".equals(a.getType())).findFirst().orElseThrow();
        assertEquals("todo-todo_1-2.py", script.getName());
        assertEquals("text/x-python", script.getContentType());
        assertTrue(script.getMetaJson().contains("\"kind\":\"python_script\""));
        assertTrue(script.getMetaJson().contains("\"source\":\"TODO_TASK\""));
        assertTrue(script.getMetaJson().contains("\"success\":true"));
        assertTrue(script.getMetaJson().contains("\"scope\":\"normal\""));
        assertEquals(eventTime.toInstant().toEpochMilli() + 7L * 24 * 60 * 60 * 1000,
                script.getExpiresAtMillis(), "retention normal 档 = 事件时间 + 7 天");
        AgentArtifactMessage jsonArtifact = first.stream()
                .filter(a -> "dataset_json".equals(a.getType())).findFirst().orElseThrow();
        assertTrue(jsonArtifact.getMetaJson().contains("\"dataset_id\":\"ds1\""));
        assertTrue(jsonArtifact.getMetaJson().contains("\"file_name\":\"ds1.json\""));
        assertTrue(first.stream().allMatch(a -> a.getUrl().startsWith("/api/agent/runs/run-1/artifacts/")));
        assertEquals("{\"columns\":[\"a\"],\"rows\":[[1]]}",
                new String(service.loadArtifactForParts(run, false, jsonArtifact.getArtifactId()).content()));

        // 注册形态：脚本 = registry 内容制品；dataset = 原位 external 引用（不复制）
        assertTrue(script.getArtifactId().startsWith("python_script:"));
        PersistentArtifactMeta scriptMeta = registry.find(script.getArtifactId()).orElseThrow();
        assertFalse(Boolean.TRUE.equals(scriptMeta.getExternal()));
        assertEquals("print(1)", registry.readContent(script.getArtifactId()));
        PersistentArtifactMeta csvMeta = registry.find(findId(first, "dataset_csv")).orElseThrow();
        assertTrue(Boolean.TRUE.equals(csvMeta.getExternal()));
        assertFalse(Boolean.TRUE.equals(csvMeta.getCleanupPath()), "引用制品：清理不动底层文件");
        assertEquals(datasetDir.resolve("ds1.csv").toAbsolutePath().normalize().toString(),
                csvMeta.getPath(), "dataset 制品必须原位引用，不得复制进 artifact 树");

        // 无双树写入：旧 {artifactRoot}/{runId}/… 快照树不再出现
        assertFalse(Files.exists(artifactRoot.resolve("run-1")), "禁止旧快照树双写");
        long scriptFiles;
        try (Stream<Path> stream = Files.list(artifactRoot.resolve("python_script"))) {
            scriptFiles = stream.count();
        }
        assertEquals(1, scriptFiles);

        // 重复 list：同 ID、run 索引项数不变、零新文件
        Set<String> firstIds = first.stream().map(AgentArtifactMessage::getArtifactId)
                .collect(java.util.stream.Collectors.toSet());
        int firstIndexSize = sets.get(RUN_LIST_PREFIX + "run-1").size();
        List<AgentArtifactMessage> second = service.listArtifacts(run, false);
        assertEquals(firstIds, second.stream().map(AgentArtifactMessage::getArtifactId)
                        .collect(java.util.stream.Collectors.toSet()),
                "重复 list 不得产生新 artifactId");
        assertEquals(firstIndexSize, sets.get(RUN_LIST_PREFIX + "run-1").size(),
                "run 索引项数不得增长");
        try (Stream<Path> stream = Files.list(artifactRoot.resolve("python_script"))) {
            assertEquals(1, stream.count(), "重复 list 不得重写内容文件");
        }
        try (Stream<Path> stream = Files.list(datasetDir)) {
            assertEquals(3, stream.count(), "dataset 树不得新增文件");
        }
        assertFalse(Files.exists(artifactRoot.resolve("run-1")));
    }

    // ===== ③ load Registry-first =====

    @Test
    void loadArtifact_shouldReadFromRegistryWhenPresent() throws Exception {
        Files.createDirectories(datasetRoot.resolve("ds1"));
        Files.writeString(datasetRoot.resolve("ds1").resolve("ds1.csv"), "a,b\n1,2\n");
        when(eventService.listByRunId("run-1")).thenReturn(todoEvents("run-1", OffsetDateTime.now()));

        AgentRun run = run("run-1", "u1");
        AgentArtifactMessage script = service.listArtifacts(run, false).stream()
                .filter(a -> "python_script".equals(a.getType())).findFirst().orElseThrow();

        // registry 命中直读：registry ID（含 ':'）不是合法 Base64，若误走 legacy 解码会直接 IAE
        AgentArtifactService.ArtifactContent content = service.loadArtifact(run, false, script.getArtifactId());
        assertEquals(script.getArtifactId(), content.artifactId());
        assertEquals("todo-todo_1-2.py", content.filename());
        assertEquals("text/x-python", content.contentType());
        assertArrayEquals("print(1)".getBytes(StandardCharsets.UTF_8), content.content());
        // 加载路径不落任何 legacy 快照
        assertFalse(Files.exists(artifactRoot.resolve("run-1")));
    }

    // ===== ④ Base64 回退只读 =====

    @Test
    void loadArtifact_shouldFallBackToLegacyBase64LocationReadOnly() throws Exception {
        OffsetDateTime eventTime = OffsetDateTime.now(ZoneOffset.UTC);
        when(eventService.listByRunId("run-1")).thenReturn(toolCallEvents("run-1", eventTime, "ds2"));

        // 旧快照树（历史部署写入）：脚本与 dataset 副本
        Path legacyScript = artifactRoot.resolve("run-1").resolve("scripts").resolve("tool-1.py");
        Files.createDirectories(legacyScript.getParent());
        Files.writeString(legacyScript, "legacy-script-content");
        Path legacyCsv = artifactRoot.resolve("run-1").resolve("datasets").resolve("ds2").resolve("ds2.csv");
        Files.createDirectories(legacyCsv.getParent());
        Files.writeString(legacyCsv, "legacy,csv");
        // dataset 源文件仍在（事件候选重放依赖其 mtime）
        Files.createDirectories(datasetRoot.resolve("ds2"));
        Files.writeString(datasetRoot.resolve("ds2").resolve("ds2.csv"), "source,csv");

        AgentRun run = run("run-1", "u1");
        String legacyScriptId = legacyId("script|run-1|tool-1");
        String legacyCsvId = legacyId("dataset_csv|run-1|ds2");

        AgentArtifactService.ArtifactContent scriptContent = service.loadArtifact(run, false, legacyScriptId);
        assertArrayEquals("legacy-script-content".getBytes(StandardCharsets.UTF_8), scriptContent.content());
        assertEquals("tool-1.py", scriptContent.filename());

        AgentArtifactService.ArtifactContent csvContent = service.loadArtifact(run, false, legacyCsvId);
        assertArrayEquals("legacy,csv".getBytes(StandardCharsets.UTF_8), csvContent.content());
        assertEquals("ds2.csv", csvContent.filename());

        // 回退只读：registry 中不得出现 legacy ID（不注册、无 meta、无 run 索引项）
        assertEquals(Optional.empty(), registry.find(legacyScriptId));
        assertEquals(Optional.empty(), registry.find(legacyCsvId));
        assertFalse(values.containsKey(META_PREFIX + legacyScriptId));
        assertTrue(sets.getOrDefault(RUN_LIST_PREFIX + "run-1", Set.of()).isEmpty());
        // 回退路径不写文件：新式内容制品目录不存在，旧快照内容未被改动
        assertFalse(Files.exists(artifactRoot.resolve("python_script")));
        assertEquals("legacy-script-content", Files.readString(legacyScript));
    }

    // ===== ⑤ 跨 run 拒绝 =====

    @Test
    void loadArtifact_shouldRejectCrossRunAccess() {
        PersistentArtifactRegistration registration = registry.registerExplicit(
                "run-1", "u1", "report", "rep-1", "report.txt", "secret", 6);

        assertEquals("run-1", service.extractRunId(registration.getArtifactId()));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.loadArtifact(run("run-2", "u1"), false, registration.getArtifactId()));
        assertEquals("artifact not found", e.getMessage());

        // legacy ID 同样拒绝跨 run
        String legacyScriptId = legacyId("script|run-1|tool-1");
        IllegalArgumentException legacy = assertThrows(IllegalArgumentException.class,
                () -> service.loadArtifact(run("run-2", "u1"), false, legacyScriptId));
        assertEquals("artifact not found", legacy.getMessage());
    }

    // ===== ⑦ retention 两档 + success-only + download 保护 =====

    @Test
    void listArtifacts_shouldKeepRetentionTiersAndSuccessOnlyFilter() throws Exception {
        OffsetDateTime eightDaysAgo = OffsetDateTime.now(ZoneOffset.UTC).minusDays(8);
        when(eventService.listByRunId("run-1"))
                .thenReturn(todoEvents("run-1", eightDaysAgo));

        // 8 天前事件：normal 档（7 天）不可见；admin 档（30 天）可见且 expiry 按 30 天计算
        assertTrue(service.listArtifacts(run("run-1", "u1"), false).stream()
                .noneMatch(a -> "python_script".equals(a.getType())));
        AgentArtifactMessage adminScript = service.listArtifacts(run("run-1", "u1"), true).stream()
                .filter(a -> "python_script".equals(a.getType())).findFirst().orElseThrow();
        assertEquals(eightDaysAgo.toInstant().toEpochMilli() + 30L * 24 * 60 * 60 * 1000,
                adminScript.getExpiresAtMillis(), "retention admin 档 = 事件时间 + 30 天");
        assertTrue(adminScript.getMetaJson().contains("\"scope\":\"admin\""));
    }

    @Test
    void listArtifacts_shouldHideFailedScriptsFromNormalScope() throws Exception {
        when(eventService.listByRunId("run-1"))
                .thenReturn(mixedSuccessToolCallEvents("run-1", OffsetDateTime.now()));

        List<AgentArtifactMessage> normal = service.listArtifacts(run("run-1", "u1"), false);
        assertEquals(1, normal.stream().filter(a -> "python_script".equals(a.getType())).count(),
                "success-only：normal scope 只列成功脚本");
        List<AgentArtifactMessage> admin = service.listArtifacts(run("run-1", "u1"), true);
        assertEquals(2, admin.stream().filter(a -> "python_script".equals(a.getType())).count(),
                "admin scope 列全部脚本");
    }

    @Test
    void loadArtifact_shouldEnforceDownloadMaxBytesOnlyForDownload() {
        PersistentArtifactRegistration registration = registry.registerExplicit(
                "run-1", "u1", "report", "big", "big.txt", "1234567890", 6);
        ReflectionTestUtils.setField(service, "downloadMaxBytes", 4L);
        AgentRun run = run("run-1", "u1");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.loadArtifact(run, false, registration.getArtifactId()));
        assertEquals("artifact too large to download", e.getMessage());

        // parts 路径不受 download max-bytes 约束
        AgentArtifactService.ArtifactContent content =
                service.loadArtifactForParts(run, false, registration.getArtifactId());
        assertArrayEquals("1234567890".getBytes(StandardCharsets.UTF_8), content.content());
    }

    // ===== helpers =====

    private static AgentRun run(String runId, String userId) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId(userId);
        run.setStartedAt(OffsetDateTime.now());
        return run;
    }

    private static AgentArtifactMessage findById(List<AgentArtifactMessage> artifacts, String artifactId) {
        return artifacts.stream().filter(a -> artifactId.equals(a.getArtifactId()))
                .findFirst().orElseThrow();
    }

    private static String findId(List<AgentArtifactMessage> artifacts, String type) {
        return artifacts.stream().filter(a -> type.equals(a.getType()))
                .map(AgentArtifactMessage::getArtifactId).findFirst().orElseThrow();
    }

    private static String legacyId(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static List<AgentRunEvent> todoEvents(String runId, OffsetDateTime eventTime) {
        AgentRunEvent e1 = new AgentRunEvent();
        e1.setRunId(runId);
        e1.setSeq(1);
        e1.setEventType("TODO_LIST_CREATED");
        e1.setPayloadJson("{\"plan\":{\"items\":[{\"id\":\"todo_1\",\"toolName\":\"executePython\",\"params\":{\"code\":\"print(1)\",\"dataset_ids\":\"ds1\"}}]}}");
        e1.setCreatedAt(eventTime);

        AgentRunEvent e2 = new AgentRunEvent();
        e2.setRunId(runId);
        e2.setSeq(2);
        e2.setEventType("TODO_STARTED");
        e2.setPayloadJson("{\"todo_id\":\"todo_1\",\"tool\":\"executePython\"}");
        e2.setCreatedAt(eventTime);

        AgentRunEvent e3 = new AgentRunEvent();
        e3.setRunId(runId);
        e3.setSeq(3);
        e3.setEventType("TODO_FINISHED");
        e3.setPayloadJson("{\"todo_id\":\"todo_1\",\"success\":true,\"output_preview\":\"{\\\"ok\\\":true,\\\"data\\\":{\\\"dataset_id\\\":\\\"ds1\\\"}}\"}");
        e3.setCreatedAt(eventTime);
        return List.of(e1, e2, e3);
    }

    private static List<AgentRunEvent> toolCallEvents(String runId, OffsetDateTime eventTime, String datasetId) {
        AgentRunEvent start = new AgentRunEvent();
        start.setRunId(runId);
        start.setSeq(1);
        start.setEventType("TOOL_CALL_STARTED");
        start.setPayloadJson("{\"tool_name\":\"executePython\",\"parameters\":{\"code\":\"print(1)\",\"dataset_ids\":\"" + datasetId + "\"}}");
        start.setCreatedAt(eventTime);

        AgentRunEvent finish = new AgentRunEvent();
        finish.setRunId(runId);
        finish.setSeq(2);
        finish.setEventType("TOOL_CALL_FINISHED");
        finish.setPayloadJson("{\"tool_name\":\"executePython\",\"success\":true,\"result_preview\":\"{\\\"ok\\\":true,\\\"data\\\":{\\\"dataset_id\\\":\\\"" + datasetId + "\\\"}}\"}");
        finish.setCreatedAt(eventTime);
        return List.of(start, finish);
    }

    private static List<AgentRunEvent> mixedSuccessToolCallEvents(String runId, OffsetDateTime eventTime) {
        AgentRunEvent start1 = new AgentRunEvent();
        start1.setRunId(runId);
        start1.setSeq(1);
        start1.setEventType("TOOL_CALL_STARTED");
        start1.setPayloadJson("{\"tool_name\":\"executePython\",\"parameters\":{\"code\":\"print(1)\"}}");
        start1.setCreatedAt(eventTime);

        AgentRunEvent finish1 = new AgentRunEvent();
        finish1.setRunId(runId);
        finish1.setSeq(2);
        finish1.setEventType("TOOL_CALL_FINISHED");
        finish1.setPayloadJson("{\"tool_name\":\"executePython\",\"success\":true,\"result_preview\":\"{\\\"ok\\\":true}\"}");
        finish1.setCreatedAt(eventTime);

        AgentRunEvent start2 = new AgentRunEvent();
        start2.setRunId(runId);
        start2.setSeq(3);
        start2.setEventType("TOOL_CALL_STARTED");
        start2.setPayloadJson("{\"tool_name\":\"executePython\",\"parameters\":{\"code\":\"boom()\"}}");
        start2.setCreatedAt(eventTime.plusSeconds(1));

        AgentRunEvent finish2 = new AgentRunEvent();
        finish2.setRunId(runId);
        finish2.setSeq(4);
        finish2.setEventType("TOOL_CALL_FINISHED");
        finish2.setPayloadJson("{\"tool_name\":\"executePython\",\"success\":false,\"result_preview\":\"{\\\"ok\\\":false}\"}");
        finish2.setCreatedAt(eventTime.plusSeconds(1));
        return List.of(start1, finish1, start2, finish2);
    }

    // ===== fake redis（values/hashes/sets 三张表；模式同 PersistentArtifactRegistryTest） =====

    @SuppressWarnings("unchecked")
    private StringRedisTemplate mockRedis() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);

        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOps);
        Mockito.doAnswer(invocation -> {
            values.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOps).set(ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.any(TimeUnit.class));
        when(valueOps.get(ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> values.get(invocation.getArgument(0)));
        Mockito.doAnswer(invocation -> values.remove(invocation.getArgument(0)) != null)
                .when(template).delete(ArgumentMatchers.anyString());

        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(template.opsForHash()).thenReturn(hashOps);
        when(hashOps.putIfAbsent(ArgumentMatchers.anyString(),
                ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(invocation -> hashes
                        .computeIfAbsent(invocation.getArgument(0), k -> new LinkedHashMap<>())
                        .putIfAbsent(invocation.getArgument(1).toString(),
                                invocation.getArgument(2).toString()) == null);
        Mockito.doAnswer(invocation -> {
            hashes.computeIfAbsent(invocation.getArgument(0), k -> new LinkedHashMap<>())
                    .put(invocation.getArgument(1).toString(), invocation.getArgument(2).toString());
            return null;
        }).when(hashOps).put(ArgumentMatchers.anyString(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
        when(hashOps.get(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    Map<String, String> h = hashes.get(invocation.getArgument(0));
                    return h == null ? null : h.get(invocation.getArgument(1).toString());
                });
        when(hashOps.delete(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    Map<String, String> h = hashes.get(invocation.getArgument(0));
                    return h == null ? 0L : (h.remove(invocation.getArgument(1).toString()) != null ? 1L : 0L);
                });

        SetOperations<String, String> setOps = mock(SetOperations.class);
        when(template.opsForSet()).thenReturn(setOps);
        when(setOps.add(ArgumentMatchers.anyString(), ArgumentMatchers.<String>any()))
                .thenAnswer(invocation -> sets
                        .computeIfAbsent(invocation.getArgument(0), k -> new LinkedHashSet<>())
                        .add(invocation.getArgument(1)) ? 1L : 0L);
        when(setOps.members(ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    Set<String> s = sets.get(invocation.getArgument(0));
                    return s == null ? new java.util.HashSet<>() : new java.util.HashSet<>(s);
                });
        when(setOps.size(ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    Set<String> s = sets.get(invocation.getArgument(0));
                    return s == null ? 0L : (long) s.size();
                });
        when(setOps.remove(ArgumentMatchers.anyString(), ArgumentMatchers.<Object>any()))
                .thenAnswer(invocation -> {
                    Set<String> s = sets.get(invocation.getArgument(0));
                    return s != null && s.remove(invocation.getArgument(1).toString()) ? 1L : 0L;
                });
        return template;
    }
}
