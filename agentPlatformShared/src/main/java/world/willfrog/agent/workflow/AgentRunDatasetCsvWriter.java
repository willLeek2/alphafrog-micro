package world.willfrog.agent.workflow;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@link AgentRunDatasetSnapshot} 渲染成 sandbox 注入用的 CSV 字符串。
 *
 * <p>两张表的 schema（§4 拍板 + MF3 扩展）：
 * <ul>
 *   <li>{@code paths_dataset.csv} — 列：
 *       {@code agent_run_dataset_id, dataset_file_path, from_ts_code, source_path}</li>
 *   <li>{@code path_manifest.csv} — 列：
 *       {@code agent_run_manifest_id, manifest_file_path, related_dataset_ids, source_path}</li>
 * </ul>
 *
 * <p>关键设计：{@code dataset_file_path} 和 {@code manifest_file_path} 在 CSV 字符串里使用
 * 占位符 {@link #SANDBOX_INPUT_PLACEHOLDER}（默认 {@code /__AF_INPUT__/}），由 Python 端
 * 在 sandbox 启动时（已知 task_id / compat_input_path 配置）替换成实际可访问的绝对路径。
 * Java 不参与 sandbox 路径解析，避免 Java/Python 双侧维护 mount 规则。</p>
 *
 * <p>MF3：新增第 4 列 {@code source_path}，由 Java 端从
 * {@link AgentRunDatasetEntry#persistedPath()} 取值（来自 T1 contract V2
 * {@code DatasetPersistedEvent.getPersistedPath()}），传给 sandbox_runner 做单文件 cp。
 * Python 端优先用 source_path 直接 cp；source_path 为空时回落老 data_dir 目录扫描逻辑，
 * 保留向后兼容。</p>
 *
 * <p>Q7 拍板：{@code manifest_file_path = NONE} 时由 Python 转译层物化临时 manifest.json，
 * CSV 字串保留 {@code NONE} 标记，Python 端识别后做物化。</p>
 */
@Slf4j
public final class AgentRunDatasetCsvWriter {

    private AgentRunDatasetCsvWriter() {
    }

    public static final String PATHS_DATASET_HEADER = "agent_run_dataset_id,dataset_file_path,from_ts_code,source_path";
    public static final String PATH_MANIFEST_HEADER = "agent_run_manifest_id,manifest_file_path,related_dataset_ids,source_path";

    /** Python 端替换的占位符前缀。 */
    public static final String SANDBOX_INPUT_PLACEHOLDER = "/__AF_INPUT__/";

    /** Manifest 元数据声明但无落盘文件（Q7 拍板）。 */
    public static final String MANIFEST_NONE_MARKER = "NONE";

    /**
     * 生成 paths_dataset.csv 内容。
     *
     * <p>{@code dataset_file_path} = {@code <SANDBOX_INPUT_PLACEHOLDER><originalId>/<sortKey>}
     * 其中 sortKey 是 01 落盘时的文件名（{@code DatasetPersistedEvent.getSortKey()}）。
     */
    public static String writePathsDatasetCsv(AgentRunDatasetSnapshot snapshot) {
        List<String> lines = new ArrayList<>();
        lines.add(PATHS_DATASET_HEADER);
        for (AgentRunDatasetEntry entry : snapshot.datasets()) {
            String sandboxPath = SANDBOX_INPUT_PLACEHOLDER + entry.originalId() + "/" + entry.sortKey();
            String sourcePath = entry.persistedPath() == null ? "" : entry.persistedPath();
            lines.add(csvRow(entry.number(), sandboxPath, entry.fromTsCode(), sourcePath));
        }
        return String.join("\n", lines) + "\n";
    }

    /**
     * 生成 path_manifest.csv 内容。{@code related_dataset_ids} 用 {@code #} 拼接。
     * Q4 拍板：manifest 编号空间独立于 dataset，但 related_dataset_ids 指向 dataset 编号。
     */
    public static String writePathManifestCsv(AgentRunDatasetSnapshot snapshot) {
        List<String> lines = new ArrayList<>();
        lines.add(PATH_MANIFEST_HEADER);
        for (AgentRunDatasetEntry entry : snapshot.manifests()) {
            String sandboxPath = entry.persistedPath() == null || entry.persistedPath().isBlank()
                    ? MANIFEST_NONE_MARKER
                    : SANDBOX_INPUT_PLACEHOLDER + entry.originalId() + "/manifest.json";
            String related = String.join("#", entry.relatedDatasetIds());
            // MF3: persistedPath 充当 source_path；NONE marker 行 source_path 也保持空（sandbox 走物化路径）
            String sourcePath = entry.persistedPath() == null ? "" : entry.persistedPath();
            lines.add(csvRow(entry.number(), sandboxPath, related, sourcePath));
        }
        return String.join("\n", lines) + "\n";
    }

    private static String csvRow(Object... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String cell = cells[i] == null ? "" : cells[i].toString();
            // RFC 4180 简化版：包含逗号 / 引号 / 换行时用双引号包裹并把内部双引号转义
            if (cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0 || cell.indexOf('\n') >= 0) {
                sb.append('"').append(cell.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(cell);
            }
        }
        return sb.toString();
    }
}
