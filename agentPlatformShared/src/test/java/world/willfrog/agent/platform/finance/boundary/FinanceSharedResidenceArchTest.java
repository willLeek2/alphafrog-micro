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
