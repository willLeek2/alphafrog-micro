package world.willfrog.alphafrogmicro.common.deployment;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一次服务部署的可信身份。
 *
 * <p>{@code deploymentId} 区分稳定环境和不同测试部署，
 * {@code generationId} 区分同一部署的不同不可变构建。Run 创建后必须同时保存两项，
 * 后续执行不能只按可复用的部署名判断所有权。</p>
 */
public record DeploymentIdentity(String deploymentId, String generationId) {

    public static final String STABLE_DEPLOYMENT_ID = "stable";
    public static final String LEGACY_GENERATION_ID = "legacy-stable";

    private static final Pattern STABLE_OR_BETA_DEPLOYMENT_ID = Pattern.compile(
            "(?:stable|[a-z0-9](?:[a-z0-9-]{1,62}[a-z0-9]))");
    private static final Pattern GENERATION_ID = Pattern.compile("gen-[0-9a-f]{64}");

    public DeploymentIdentity {
        deploymentId = requireDeploymentId(deploymentId);
        generationId = requireActiveGenerationId(generationId);
    }

    public static String requireDeploymentId(String value) {
        String normalized = requireText(value, "deployment_id");
        if (!STABLE_OR_BETA_DEPLOYMENT_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("deployment_id 格式不合法");
        }
        return normalized;
    }

    public static String requirePersistedGenerationId(String value) {
        String normalized = requireText(value, "deployment_generation_id");
        if (!LEGACY_GENERATION_ID.equals(normalized)
                && !GENERATION_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("deployment_generation_id 格式不合法");
        }
        return normalized;
    }

    public static String requireActiveGenerationId(String value) {
        String normalized = requirePersistedGenerationId(value);
        if (LEGACY_GENERATION_ID.equals(normalized)) {
            throw new IllegalArgumentException("legacy-stable 只用于历史数据，不能作为运行实例代际");
        }
        return normalized;
    }

    public void requireExactMatch(String requestedDeploymentId, String requestedGenerationId) {
        String requestedDeployment = requireDeploymentId(requestedDeploymentId);
        String requestedGeneration = requireActiveGenerationId(requestedGenerationId);
        if (!Objects.equals(deploymentId, requestedDeployment)
                || !Objects.equals(generationId, requestedGeneration)) {
            throw new DeploymentIdentityMismatchException(
                    "请求的部署身份与当前服务实例不一致");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        String normalized = value.trim();
        if (!value.equals(normalized)) {
            throw new IllegalArgumentException(fieldName + " 不能包含首尾空白");
        }
        return normalized;
    }
}
