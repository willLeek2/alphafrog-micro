package world.willfrog.agent.platform.finance.boundary;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * D22 §4.1 金融运行时边界架构测试（fail-closed）。
 *
 * <p>扫描本模块（agentPlatformShared）编译产物 {@code target/classes} 中
 * {@code world/willfrog/agent/platform/} 下的全部主类，凡符合金融识别口径
 * （FQCN 含 {@code .finance.} 包段，或简单类名以 {@code Finance} 开头）的类，
 * 必须与 {@link FinanceSharedResidenceAllowlist} 的精确 FQCN 封闭集合
 * <b>完全相等</b>：</p>
 * <ul>
 *   <li>扫描到但不在清单内 → 失败（未审批的金融扩张，对应 D22 §6 红线 1）；</li>
 *   <li>清单内但已不存在 → 失败（清单必须随删除同步收缩，不留陈旧条目）；</li>
 *   <li>扫描目录缺失或识别结果为空 → 失败（防止扫描失效导致测试空转假绿）。</li>
 * </ul>
 *
 * <p>口径边界：本测试只能机械识别遵守命名约定的金融类（{@code Finance} 前缀或
 * {@code .finance.} 包）。绕过命名约定的新金融类不在机械拦截范围内，由评审人工拦截
 * ——该约定已写入 {@code docs/d22-finance-residence-allowlist-v1.md} §3。</p>
 */
class FinanceSharedResidenceArchTest {

    private static final String PLATFORM_BASE_PACKAGE_PATH = "world/willfrog/agent/platform";
    private static final String PLATFORM_BASE_PACKAGE = "world.willfrog.agent.platform";

    @Test
    void detectedFinanceClasses_exactlyMatchClosedAllowlist() throws Exception {
        Path classesRoot = mainClassesRoot();
        Path baseDir = classesRoot.resolve(PLATFORM_BASE_PACKAGE_PATH);
        assertTrue(Files.isDirectory(baseDir),
                "主类扫描目录不存在，扫描失效（fail-closed）：" + baseDir);

        Set<String> detected = detectFinanceClasses(baseDir);
        assertFalse(detected.isEmpty(),
                "未识别到任何金融类，扫描疑似失效（fail-closed）");

        Set<String> allowed = new TreeSet<>(FinanceSharedResidenceAllowlist.allowedFinanceClasses());
        Set<String> notAllowed = new TreeSet<>(detected);
        notAllowed.removeAll(allowed);
        Set<String> stale = new TreeSet<>(allowed);
        stale.removeAll(detected);

        assertTrue(notAllowed.isEmpty(),
                "发现未列入封闭清单的金融类（D22 §6 红线 1，须先审批并更新清单）：" + notAllowed);
        assertTrue(stale.isEmpty(),
                "清单含有已不存在的陈旧条目（清单必须与包树同步收缩）：" + stale);
        assertEquals(allowed.size(), detected.size(),
                "金融类集合与封闭清单必须精确相等");
    }

    @Test
    void allowlistEntries_areExactFqcnForm() {
        Set<String> allowed = FinanceSharedResidenceAllowlist.allowedFinanceClasses();
        assertFalse(allowed.isEmpty(), "封闭清单不得为空");
        for (String entry : allowed) {
            assertTrue(entry.startsWith(PLATFORM_BASE_PACKAGE + "."),
                    "清单条目必须是本模块精确 FQCN：" + entry);
            assertFalse(entry.contains("*") || entry.contains(" ") || entry.contains("$"),
                    "清单条目不得含通配符/空白/内部类分隔符：" + entry);
        }
    }

    @Test
    void detectionRule_catchesHypotheticalUnapprovedFinanceClass() {
        // 反测：识别口径本身必须会咬住假想的新金融 Mapper / 新 finance 包类，
        // 否则主测试的「完全相等」失去意义
        assertTrue(isFinanceSpecific(
                "world.willfrog.agent.platform.mapper.FinanceNewUnapprovedMapper"));
        assertTrue(isFinanceSpecific(
                "world.willfrog.agent.platform.finance.FinanceNewRuntime"));
        assertTrue(isFinanceSpecific(
                "world.willfrog.agent.platform.finance.sub.SomeHelper"));
        assertFalse(isFinanceSpecific(
                "world.willfrog.agent.platform.service.AgentCreditService"));
        assertFalse(isFinanceSpecific(
                "world.willfrog.agent.platform.mapper.AgentRunMapper"));
    }

    @Test
    void allowlistClass_itselfIsListedAndLoadable() throws ClassNotFoundException {
        // 治理工件自指：清单类自身是金融识别口径下的类，必须在清单内且可加载
        String self = "world.willfrog.agent.platform.finance.boundary.FinanceSharedResidenceAllowlist";
        assertTrue(FinanceSharedResidenceAllowlist.allowedFinanceClasses().contains(self));
        assertTrue(isFinanceSpecific(self));
        Class.forName(self);
    }

    /**
     * 定位本模块主类编译产物目录（target/classes）。
     *
     * <p>以本测试类自身的 code source（target/test-classes）的同级 classes 目录为准；
     * 找不到即失败，绝不在错误的目录上空转通过。</p>
     */
    private Path mainClassesRoot() throws Exception {
        URI testClassesUri = FinanceSharedResidenceArchTest.class
                .getProtectionDomain().getCodeSource().getLocation().toURI();
        Path testClasses = Paths.get(testClassesUri);
        Path classes = testClasses.getParent().resolve("classes");
        if (!Files.isDirectory(classes)) {
            fail("未找到本模块主类编译目录（fail-closed）：" + classes);
        }
        return classes;
    }

    private Set<String> detectFinanceClasses(Path baseDir) throws IOException {
        Set<String> detected = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(baseDir)) {
            walk.filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> !p.getFileName().toString().contains("$"))
                    .forEach(p -> {
                        String relative = baseDir.relativize(p).toString();
                        String fqcn = PLATFORM_BASE_PACKAGE + "."
                                + relative.replace('/', '.').replace('\\', '.')
                                .replaceAll("\\.class$", "");
                        if (isFinanceSpecific(fqcn)) {
                            detected.add(fqcn);
                        }
                    });
        }
        return detected;
    }

    /**
     * 金融识别口径（与边界文档 §3 命名约定一致）：
     * FQCN 含 {@code .finance.} 包段，或简单类名以 {@code Finance} 开头。
     */
    static boolean isFinanceSpecific(String fqcn) {
        if (fqcn == null || fqcn.isBlank()) {
            return false;
        }
        if (fqcn.contains(".finance.")) {
            return true;
        }
        String simpleName = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        return simpleName.startsWith("Finance");
    }
}
