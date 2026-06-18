package world.willfrog.agent.service.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * workspace reference 校验器。
 *
 * <p>三类 reference 校验：</p>
 * <ol>
 *   <li>atomic dataset：{dataset.path}/{id}/{id}.csv 存在</li>
 *   <li>dataset_manifest：{dataset.path}/{id}/{id}.manifest.json 存在，且每个 ready member 目录存在；
 *       ready member 缺失时把对应 member 标 status=broken 并计入 brokenRefs</li>
 *   <li>python_script：inline code 非空（写入侧已过滤）</li>
 * </ol>
 *
 * <p>同一次 traversal 同时产出 manifest.json.brokenRefs[] 和 meta.json.health.brokenRefs，
 * 保证两个文件状态一致。</p>
 *
 * @author wang
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceHealthVerifier {

    private final WorkspacePathResolver pathResolver;

    @Value("${agent.tools.market-data.dataset.path:/data/agent_datasets}")
    private String datasetPath;

    /**
     * 校验 collected assets 中所有 dataset reference 的健康状态。
     *
     * @param assets collector 汇总的资产
     * @return 一次 traversal 的 WorkspaceHealth（brokenRefs 与 manifest member 状态同步）
     */
    public WorkspaceHealth verify(CollectedAssets assets) {
        if (assets == null) {
            throw new IllegalArgumentException("assets 不能为空");
        }
        List<BrokenRef> brokenRefs = new ArrayList<>();
        List<ManifestMemberView> manifestMembers = new ArrayList<>();
        int totalRefs = 0;

        for (String datasetId : assets.datasetIds()) {
            if (datasetId == null || datasetId.isBlank()) {
                continue;
            }
            totalRefs++;
            Path datasetDir;
            try {
                datasetDir = pathResolver.resolveDatasetRef(datasetId);
            } catch (Exception e) {
                brokenRefs.add(new BrokenRef(datasetId, "<unresolved>",
                        "dataset id 越界: " + e.getMessage()));
                continue;
            }

            Path manifestFile = datasetDir.resolve(datasetId + ".manifest.json");
            if (Files.exists(manifestFile)) {
                // 这是 dataset_manifest 资产，展开 members[] 检查 ready member
                List<ManifestMemberView> members = parseAndVerifyManifest(datasetId, manifestFile, brokenRefs);
                manifestMembers.addAll(members);
                continue;
            }

            // atomic dataset 校验
            Path csvFile = datasetDir.resolve(datasetId + ".csv");
            if (!Files.exists(csvFile) || !Files.isRegularFile(csvFile)) {
                brokenRefs.add(new BrokenRef(datasetId, csvFile.toString(),
                        "atomic dataset csv 缺失"));
            }
        }

        return new WorkspaceHealth(totalRefs, brokenRefs, manifestMembers);
    }

    private List<ManifestMemberView> parseAndVerifyManifest(String manifestId, Path manifestFile,
                                                            List<BrokenRef> brokenRefs) {
        List<ManifestMemberView> result = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode root = mapper.readTree(manifestFile.toFile());
            JsonNode members = root.path("members");
            if (!members.isArray()) {
                brokenRefs.add(new BrokenRef(manifestId, manifestFile.toString(),
                        "manifest.json 缺 members 数组"));
                return result;
            }
            for (JsonNode member : members) {
                String tsCode = member.path("tsCode").asText("");
                String memberDatasetId = member.path("datasetId").asText("");
                String status = member.path("status").asText("");
                int rowCount = member.path("rowCount").asInt(0);
                String effectiveStatus = status;
                if ("ready".equals(status) && memberDatasetId != null && !memberDatasetId.isBlank()) {
                    // 统一走 pathResolver.resolveDatasetRef：路径越界 / 软链绕过 / 非法 id 都会被一次性拦下
                    Path memberDir;
                    try {
                        memberDir = pathResolver.resolveDatasetRef(memberDatasetId);
                    } catch (Exception e) {
                        effectiveStatus = "broken";
                        brokenRefs.add(new BrokenRef(memberDatasetId, "<unresolved>",
                                "manifest member datasetId 越界: " + e.getMessage()));
                        result.add(new ManifestMemberView(manifestId, tsCode, memberDatasetId, effectiveStatus, rowCount));
                        continue;
                    }
                    Path memberCsvPath = memberDir.resolve(memberDatasetId + ".csv");
                    if (!Files.exists(memberCsvPath) || !Files.isRegularFile(memberCsvPath)) {
                        effectiveStatus = "broken";
                        brokenRefs.add(new BrokenRef(memberDatasetId, memberCsvPath.toString(),
                                "manifest ready member 缺失"));
                    }
                }
                result.add(new ManifestMemberView(manifestId, tsCode, memberDatasetId, effectiveStatus, rowCount));
            }
        } catch (IOException e) {
            log.warn("parse manifest 失败: manifestId={} file={}", manifestId, manifestFile, e);
            brokenRefs.add(new BrokenRef(manifestId, manifestFile.toString(),
                    "manifest.json 解析失败: " + e.getMessage()));
        }
        return result;
    }
}
