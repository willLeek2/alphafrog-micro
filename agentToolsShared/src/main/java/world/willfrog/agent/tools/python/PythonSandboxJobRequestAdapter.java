package world.willfrog.agent.tools.python;

import com.google.protobuf.util.JsonFormat;
import world.willfrog.agent.platform.dataanalysis.CanonicalSandboxCreateSpec;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisEstimate;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisTerminalEnvelope;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.tools.sandboxjob.SandboxJobRequestAdapter;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;

import java.nio.charset.StandardCharsets;

/**
 * executePython 的请求适配器：ExecuteRequest 的规范化规格重建、指纹与恢复重放。
 *
 * <p>canonical 规格的字段在派发前已由容量准入写入请求本体（withCapacityRequest），
 * 因此从请求字段即可逐项重建同一规格；恢复重放按锚点里冻存的 createRequestJson
 * 反序列化。</p>
 */
public final class PythonSandboxJobRequestAdapter implements SandboxJobRequestAdapter<ExecuteRequest> {

    @Override
    public String toolName() {
        return ToolJobAnchor.EXECUTE_PYTHON_TOOL;
    }

    @Override
    public CanonicalSandboxCreateSpec buildCanonicalSpec(ExecuteRequest request) {
        return new CanonicalSandboxCreateSpec(
                request.getCanonicalSpecSchemaVersion(),
                request.getOperationId(),
                request.getCodeHash(),
                request.getImmutableDatasetSnapshotDigest(),
                DataAnalysisResourceClass.valueOf(request.getResourceClass()),
                request.getMemoryLimitBytes(),
                request.getTimeoutMillis(),
                request.getRuntimeEnvironmentVersion(),
                request.getLibrariesDigest(),
                request.getSandboxOptionsDigest());
    }

    /** 把准入结果与 canonical identity 写入真正发送给 Sandbox 的请求。 */
    @Override
    public ExecuteRequest enrichWithCapacity(ExecuteRequest baseRequest,
                                             DataAnalysisReservation reservation,
                                             DataAnalysisEstimate estimate,
                                             CanonicalSandboxCreateSpec spec) {
        return baseRequest.toBuilder()
                .setResourceClass(reservation.resourceClass().name())
                .setEstimatedRows(estimate.estimatedRows())
                .setEstimatedBytes(estimate.estimatedBytes())
                .setFileCount(estimate.fileCount())
                .setCapacityUnits(estimate.capacityUnits())
                .setOperationId(spec.operationId())
                .setRequestFingerprint(spec.requestFingerprint())
                .setMemoryLimitBytes(spec.memoryLimitBytes())
                .setTimeoutMillis(spec.timeoutMillis())
                .setRuntimeEnvironmentVersion(spec.runtimeEnvironmentVersion())
                .setCanonicalSpecSchemaVersion(spec.schemaVersion())
                .setCodeHash(spec.codeHash())
                .setImmutableDatasetSnapshotDigest(spec.immutableDatasetSnapshotDigest())
                .setLibrariesDigest(spec.librariesDigest())
                .setSandboxOptionsDigest(spec.sandboxOptionsDigest())
                .build();
    }

    @Override
    public String requestFingerprint(ExecuteRequest request) {
        return request.getRequestFingerprint();
    }

    @Override
    public ExecuteRequest parseStoredCreateRequest(String createRequestJson) {
        try {
            ExecuteRequest.Builder builder = ExecuteRequest.newBuilder();
            JsonFormat.parser().merge(createRequestJson, builder);
            return builder.build();
        } catch (Exception parseFailure) {
            throw new IllegalStateException("stored create request could not be replayed", parseFailure);
        }
    }

    /** 恢复注入给模型看的载荷预览：有界截断的原代码（与终态预览同一 16KB UTF-8 安全截断）。 */
    @Override
    public String payloadPreview(String createRequestJson) {
        if (createRequestJson == null || createRequestJson.isBlank()) {
            return null;
        }
        return boundedPreview(parseStoredCreateRequest(createRequestJson).getCode());
    }

    /** 与 ToolJobFinalizer.boundedPreview 同一算法：16KB UTF-8 安全截断（含后缀）。 */
    private static String boundedPreview(String s) {
        if (s == null) return null;
        String suffix = "…(truncated)";
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        int max = DataAnalysisTerminalEnvelope.MAX_RESULT_PREVIEW_BYTES;
        if (raw.length <= max) return s;
        byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);
        int cut = max - suffixBytes.length;
        if (cut <= 0) return suffix;
        while (cut > 0 && (raw[cut] & 0xC0) == 0x80) cut--;
        return new String(raw, 0, cut, StandardCharsets.UTF_8) + suffix;
    }
}
