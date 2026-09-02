package world.willfrog.alphafrogmicro.common.deployment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** 根据部署单版本、完整提交和每个服务的不可变镜像引用计算部署代际。 */
public final class DeploymentGenerationId {

    private static final long MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991L;
    private static final Pattern GIT_COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern SERVICE_NAME = Pattern.compile("[a-z][a-z0-9-]{0,95}");
    private static final Pattern IMAGE_REFERENCE = Pattern.compile("[^\\s@]+@sha256:[0-9a-f]{64}");

    private DeploymentGenerationId() {
    }

    public static String compute(long manifestVersion,
                                 String gitCommit,
                                 Map<String, String> serviceImages) {
        if (manifestVersion < 1 || manifestVersion > MAX_SAFE_JSON_INTEGER) {
            throw new IllegalArgumentException("manifest_version 必须是 JSON 跨语言安全整数");
        }
        String commit = requireMatch(gitCommit, GIT_COMMIT, "git_commit");
        if (serviceImages == null || serviceImages.isEmpty()) {
            throw new IllegalArgumentException("service_images 不能为空");
        }

        TreeMap<String, String> sorted = new TreeMap<>();
        serviceImages.forEach((serviceName, imageReference) -> {
            String name = requireMatch(serviceName, SERVICE_NAME, "service_name");
            String image = requireMatch(imageReference, IMAGE_REFERENCE, "image_reference");
            if (sorted.put(name, image) != null) {
                throw new IllegalArgumentException("service_name 不能重复");
            }
        });

        StringBuilder canonical = new StringBuilder()
                .append("alphafrog-deployment-generation-v1\n")
                .append("manifest-version:").append(manifestVersion).append('\n')
                .append("git-commit:").append(commit).append('\n');
        sorted.forEach((name, image) -> canonical
                .append("service:").append(name).append('\0').append(image).append('\n'));
        return "gen-" + sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String requireMatch(String value, Pattern pattern, String fieldName) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " 格式不合法");
        }
        return value;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                hex.append(Character.forDigit(value & 0x0f, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }
}
