package world.willfrog.agentlangchain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流量分发与业务逻辑分离的源码合同（需求 0913-02 改动三第 3、4 条）：
 * 业务包不能引用部署身份读取口 / 泳道环境，也不能自己调用带部署代际栅栏的 SQL。
 *
 * <p>允许的例外只有两个包：{@code gateway}（分发判定的唯一出口）与 {@code deployment}
 * （代际退役与孤儿清扫的合法归处）。测试源码不在扫描范围（夹具允许自带身份）。</p>
 */
class TrafficDistributionIsolationContractTest {

    private static final List<String> ALLOWED_PACKAGES = List.of("gateway/", "deployment/");

    /** 业务包里不允许出现的符号：身份读取口、泳道 API、带代际栅栏的 SQL 调用。 */
    private static final List<Pattern> FORBIDDEN_SYMBOLS = List.of(
            Pattern.compile("DeploymentIdentityProvider"),
            Pattern.compile("AF_DEPLOYMENT_ID"),
            Pattern.compile("AF_DEPLOYMENT_GENERATION_ID"),
            Pattern.compile("(?<![A-Za-z0-9_])LaneContext\\b"),
            Pattern.compile("ForDeployment"));

    /**
     * 唯一允许出现 {@code DeploymentIdentity}「值」的位置：受理入口。
     * 它从 gateway 取一次身份、在创建 Run 时写一次，之后不再参与任何判定。
     */
    private static final List<String> IDENTITY_VALUE_ALLOWED = List.of(
            "facade/AgentLangchainRunService.java");

    private static final Pattern IDENTITY_VALUE = Pattern.compile("(?<![A-Za-z0-9_])DeploymentIdentity\\b");

    private final Path businessRoot = Path.of(System.getProperty("user.dir"))
            .resolve("src/main/java/world/willfrog/agentlangchain");

    @Test
    void businessPackagesDoNotReadDeploymentIdentityLaneOrFencedSql() throws IOException {
        for (Path source : businessSources()) {
            String text = read(source);
            for (Pattern symbol : FORBIDDEN_SYMBOLS) {
                assertThat(symbol.matcher(text).find())
                        .as("%s 不得引用 %s（分发判定收在 gateway / deployment 包）",
                                relative(source), symbol.pattern())
                        .isFalse();
            }
        }
    }

    @Test
    void onlyTheAdmissionEntryCarriesTheDeploymentIdentityValue() throws IOException {
        for (Path source : businessSources()) {
            if (IDENTITY_VALUE_ALLOWED.contains(relative(source))) {
                continue;
            }
            assertThat(IDENTITY_VALUE.matcher(read(source)).find())
                    .as("%s 不得出现 DeploymentIdentity（只有受理入口写一次身份）", relative(source))
                    .isFalse();
        }
    }

    @Test
    void gatewayPackageIsWhereTheIdentityAndLaneReachTheBusinessCode() {
        assertThat(read(businessRoot.resolve("gateway/RunOwnershipGateway.java")))
                .contains("DeploymentIdentityProvider");
        assertThat(read(businessRoot.resolve("gateway/LaneScopeGateway.java")))
                .contains("LaneContext");
    }

    private List<Path> businessSources() throws IOException {
        try (var paths = Files.walk(businessRoot)) {
            List<Path> sources = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !isAllowed(path))
                    .toList();
            assertThat(sources).isNotEmpty();
            return sources;
        }
    }

    private String relative(Path source) {
        return businessRoot.relativize(source).toString().replace('\\', '/');
    }

    private static boolean isAllowed(Path path) {
        String relative = path.toString().replace('\\', '/');
        int index = relative.indexOf("/world/willfrog/agentlangchain/");
        String packagePath = relative.substring(index + "/world/willfrog/agentlangchain/".length());
        return ALLOWED_PACKAGES.stream().anyMatch(packagePath::startsWith);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
