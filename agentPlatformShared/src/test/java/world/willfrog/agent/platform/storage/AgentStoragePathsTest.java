package world.willfrog.agent.platform.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D04 统一存储路径门面测试（W5 task #105）。
 *
 * <p>覆盖：新键 → 旧键别名 → 默认值解析链、空白回退、dataset 旧键别名、
 * artifactRoot 第二 legacy alias（D22-5.1.3 K3）与双旧键冲突 fail-closed、
 * §4.3 可达性失败信号（requireWritableRoot / verifyDumpTarget）。
 */
class AgentStoragePathsTest {

    @TempDir
    Path tempDir;


















}
