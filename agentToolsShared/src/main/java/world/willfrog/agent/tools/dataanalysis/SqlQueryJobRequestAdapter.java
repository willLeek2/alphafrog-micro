package world.willfrog.agent.tools.dataanalysis;

import world.willfrog.agent.platform.dataanalysis.CanonicalSandboxCreateSpec;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisEstimate;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.tools.python.PythonSandboxJobRequestAdapter;
import world.willfrog.agent.tools.sandboxjob.SandboxJobRequestAdapter;
import world.willfrog.agent.tools.sandboxjob.SandboxJobResponses;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * executeQuery 的请求适配器：ExecuteRequest 的规范化规格重建、指纹与恢复重放。
 *
 * <p>executeQuery 与 executePython 复用同一个沙箱 RPC 通道（ExecuteRequest proto），
 * canonical 规格、容量写入与恢复重放的 proto 机械操作完全相同，因此这三段委托给
 * {@link PythonSandboxJobRequestAdapter}；本类只保留工具名与载荷预览两处工具语义：
 * 恢复注入给模型看的预览是模型写的 SQL，而不是平台渲染后的运行器脚本。</p>
 */
public final class SqlQueryJobRequestAdapter implements SandboxJobRequestAdapter<ExecuteRequest> {

    /** 渲染后的运行器脚本里内嵌规格的行：{@code SPEC_B64 = "<base64(json)>"}。 */
    private static final Pattern SPEC_LINE = Pattern.compile("SPEC_B64 = \"([A-Za-z0-9+/=]+)\"");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PythonSandboxJobRequestAdapter protoMechanics = new PythonSandboxJobRequestAdapter();

    @Override
    public String toolName() {
        return ToolJobAnchor.EXECUTE_QUERY_TOOL;
    }

    @Override
    public CanonicalSandboxCreateSpec buildCanonicalSpec(ExecuteRequest request) {
        return protoMechanics.buildCanonicalSpec(request);
    }

    @Override
    public ExecuteRequest enrichWithCapacity(ExecuteRequest baseRequest,
                                             DataAnalysisReservation reservation,
                                             DataAnalysisEstimate estimate,
                                             CanonicalSandboxCreateSpec spec) {
        return protoMechanics.enrichWithCapacity(baseRequest, reservation, estimate, spec);
    }

    @Override
    public String durableCreateRequestJson(ExecuteRequest request) {
        return protoMechanics.durableCreateRequestJson(request);
    }

    @Override
    public String requestFingerprint(ExecuteRequest request) {
        return protoMechanics.requestFingerprint(request);
    }

    @Override
    public ExecuteRequest parseStoredCreateRequest(String createRequestJson) {
        return protoMechanics.parseStoredCreateRequest(createRequestJson);
    }

    /**
     * 恢复注入给模型看的载荷预览：从冻存的渲染脚本里解出模型当初写的 SQL 再截断；
     * 解不出来（脚本形态异常）时退化为整段脚本的有界截断。
     */
    @Override
    public String payloadPreview(String createRequestJson) {
        if (createRequestJson == null || createRequestJson.isBlank()) {
            return null;
        }
        String code = parseStoredCreateRequest(createRequestJson).getCode();
        String sql = extractSql(code);
        return SandboxJobResponses.boundedPreview(sql != null ? sql : code);
    }

    /** 从渲染后的运行器脚本中解出模型 SQL；找不到规格行或规格损坏时返回 null。 */
    static String extractSql(String renderedCode) {
        if (renderedCode == null) {
            return null;
        }
        Matcher matcher = SPEC_LINE.matcher(renderedCode);
        if (!matcher.find()) {
            return null;
        }
        try {
            String json = new String(Base64.getDecoder().decode(matcher.group(1)), StandardCharsets.UTF_8);
            JsonNode spec = JSON.readTree(json);
            JsonNode sql = spec.get("sql");
            return sql != null && sql.isTextual() ? sql.asText() : null;
        } catch (Exception decodeFailure) {
            return null;
        }
    }
}
