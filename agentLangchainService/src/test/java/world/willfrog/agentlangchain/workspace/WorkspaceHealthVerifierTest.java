package world.willfrog.agentlangchain.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceHealthVerifierTest {

    @TempDir
    Path tempRoot;

    WorkspacePathResolver pathResolver;
    WorkspaceHealthVerifier verifier;

    @BeforeEach
    void setUp() {
        pathResolver = new WorkspacePathResolver(
                tempRoot.resolve("workspace").toString(),
                tempRoot.resolve("datasets").toString()
        );
        verifier = new WorkspaceHealthVerifier(pathResolver);
        // datasetPath 是 private final 字段（@Value 注入），通过反射设置避免改动 Verifier 构造签名
        try {
            java.lang.reflect.Field f = WorkspaceHealthVerifier.class.getDeclaredField("datasetPath");
            f.setAccessible(true);
            f.set(verifier, tempRoot.resolve("datasets").toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void verify_atomicDatasetExists_noBroken() throws Exception {
        // 准备 dataset 目录和 csv
        String datasetId = "stock-000001.SZ-20240101-20240131-abc";
        Path datasetDir = tempRoot.resolve("datasets").resolve(datasetId);
        Files.createDirectories(datasetDir);
        Files.writeString(datasetDir.resolve(datasetId + ".csv"), "header\nrow\n");

        CollectedAssets assets = new CollectedAssets(List.of(), List.of(), List.of(datasetId));
        WorkspaceHealth health = verifier.verify(assets);

        assertEquals(1, health.totalRefs());
        assertEquals(0, health.brokenRefs().size());
    }

    @Test
    void verify_atomicDatasetMissing_markedBroken() {
        // dataset 目录不存在
        String datasetId = "stock-000001.SZ-20240101-20240131-abc";
        CollectedAssets assets = new CollectedAssets(List.of(), List.of(), List.of(datasetId));
        WorkspaceHealth health = verifier.verify(assets);

        assertEquals(1, health.totalRefs());
        assertEquals(1, health.brokenRefs().size());
        assertEquals(datasetId, health.brokenRefs().get(0).assetId());
    }

    @Test
    void verify_manifestReadyMemberMissing_markedBroken() throws Exception {
        // 准备 manifest 资产，member 1 存在，member 2 缺失
        String manifestId = "manifest-stock_daily-20240101-20240131-uuid8";
        String member1 = "stock-000001.SZ-20240101-20240131-aaaa";
        String member2 = "stock-000002.SZ-20240101-20240131-bbbb";
        Path manifestDir = tempRoot.resolve("datasets").resolve(manifestId);
        Files.createDirectories(manifestDir);

        // member 1 存在
        Path member1Dir = tempRoot.resolve("datasets").resolve(member1);
        Files.createDirectories(member1Dir);
        Files.writeString(member1Dir.resolve(member1 + ".csv"), "data\n");

        String manifestJson = "{\n" +
                "  \"manifestId\": \"" + manifestId + "\",\n" +
                "  \"members\": [\n" +
                "    {\"tsCode\": \"000001.SZ\", \"datasetId\": \"" + member1 + "\", \"status\": \"ready\", \"rowCount\": 21},\n" +
                "    {\"tsCode\": \"000002.SZ\", \"datasetId\": \"" + member2 + "\", \"status\": \"ready\", \"rowCount\": 0}\n" +
                "  ]\n" +
                "}";
        Files.writeString(manifestDir.resolve(manifestId + ".manifest.json"), manifestJson);

        CollectedAssets assets = new CollectedAssets(List.of(), List.of(), List.of(manifestId));
        WorkspaceHealth health = verifier.verify(assets);

        assertEquals(1, health.totalRefs());
        // member 1 ready, member 2 broken (ready 但文件缺失)
        assertEquals(1, health.brokenRefs().size());
        assertEquals(member2, health.brokenRefs().get(0).assetId());
        // manifestMembers 中 member 2 应该是 broken
        boolean foundBroken = health.manifestMembers().stream()
                .anyMatch(m -> m.datasetId().equals(member2) && "broken".equals(m.status()));
        assertTrue(foundBroken);
    }
}
