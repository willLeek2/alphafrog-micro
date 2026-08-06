package world.willfrog.agent.platform.dataanalysis;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Sandbox create 的跨语言幂等请求契约。
 *
 * <p>编码采用固定字段顺序的 UTF-8 length-prefix 格式：
 * {@code fieldName:utf8ByteLength:value\n}。Python Sandbox 必须按相同规则重新计算指纹，
 * 不能直接信任客户端传入的 {@code requestFingerprint}。</p>
 */
public record CanonicalSandboxCreateSpec(
        String schemaVersion,
        String operationId,
        String codeHash,
        String immutableDatasetSnapshotDigest,
        DataAnalysisResourceClass resourceClass,
        long memoryLimitBytes,
        long timeoutMillis,
        String runtimeEnvironmentVersion,
        String librariesDigest,
        String sandboxOptionsDigest) {

    public static final String CURRENT_SCHEMA_VERSION = "sandbox_create_v1";
    private static final String SHA_256_PREFIX = "sha256:";

    public CanonicalSandboxCreateSpec {
        schemaVersion = DataAnalysisContractSupport.requireText(schemaVersion, "schemaVersion");
        operationId = DataAnalysisContractSupport.requireText(operationId, "operationId");
        codeHash = normalizeSha256(codeHash, "codeHash");
        immutableDatasetSnapshotDigest = normalizeSha256(
                immutableDatasetSnapshotDigest,
                "immutableDatasetSnapshotDigest");
        if (resourceClass == null) {
            throw new IllegalArgumentException("resourceClass must not be null");
        }
        if (memoryLimitBytes <= 0) {
            throw new IllegalArgumentException("memoryLimitBytes must be positive");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        runtimeEnvironmentVersion = DataAnalysisContractSupport.requireText(
                runtimeEnvironmentVersion,
                "runtimeEnvironmentVersion");
        librariesDigest = normalizeSha256(librariesDigest, "librariesDigest");
        sandboxOptionsDigest = normalizeSha256(sandboxOptionsDigest, "sandboxOptionsDigest");
    }

    public byte[] canonicalBytes() {
        return canonicalBytes(true);
    }

    /**
     * 用于代码修复判重的请求内容指纹。
     *
     * <p>{@link #requestFingerprint()} 必须包含 operationId，才能保护同一次 Sandbox
     * create 的 exactly-once 身份；模型修复会产生新的 toolCallId/operationId，因此
     * 不能用它判断“代码和有效参数是否原样重放”。本指纹只排除 operationId，其余
     * 代码、数据快照、资源、运行时、依赖和 options 全部保持相同编码契约。</p>
     */
    public String repairRequestFingerprint() {
        return sha256(canonicalBytes(false));
    }

    private byte[] canonicalBytes(boolean includeOperationId) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(512);
        append(output, "schemaVersion", schemaVersion);
        if (includeOperationId) {
            append(output, "operationId", operationId);
        }
        append(output, "codeHash", codeHash);
        append(output, "immutableDatasetSnapshotDigest", immutableDatasetSnapshotDigest);
        append(output, "resourceClass", resourceClass.name());
        append(output, "memoryLimitBytes", Long.toString(memoryLimitBytes));
        append(output, "timeoutMillis", Long.toString(timeoutMillis));
        append(output, "runtimeEnvironmentVersion", runtimeEnvironmentVersion);
        append(output, "librariesDigest", librariesDigest);
        append(output, "sandboxOptionsDigest", sandboxOptionsDigest);
        return output.toByteArray();
    }

    public String requestFingerprint() {
        return sha256(canonicalBytes());
    }

    private static String sha256(byte[] canonicalBytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalBytes);
            return SHA_256_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not provide SHA-256", e);
        }
    }

    private static void append(ByteArrayOutputStream output, String field, String value) {
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeBytes((field + ":" + valueBytes.length + ":").getBytes(StandardCharsets.UTF_8));
        output.writeBytes(valueBytes);
        output.write('\n');
    }

    private static String normalizeSha256(String value, String field) {
        String normalized = DataAnalysisContractSupport.requireText(value, field)
                .toLowerCase(Locale.ROOT);
        if (normalized.startsWith(SHA_256_PREFIX)) {
            normalized = normalized.substring(SHA_256_PREFIX.length());
        }
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 hex digest");
        }
        return SHA_256_PREFIX + normalized;
    }
}
