package world.willfrog.agent.tools.dataset;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 1 dataset manifest view (见 dataset batching markdown §四.1)。
 * 一个 manifest 描述一组原子 dataset，自身也是一个可被 executePython 读取的逻辑 dataset。
 * 落盘位置：{@code {dataset.path}/{manifestId}/{manifestId}.manifest.json} + .meta.json。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasetManifest {

    public static final String KIND = "dataset_manifest";

    /** 稳定 manifestId，命名形如 {@code manifest-{dataType}-{start}-{end}-{hash8}}。 */
    private String manifestId;

    /** 固定为 {@link #KIND}，供 sandbox / workspace 区分 manifest 与 atomic dataset。 */
    private String kind;

    /** 内部数据来源，例如 {@code stock_daily} / {@code index_daily} / {@code etf_daily}。 */
    private String dataType;

    private String startDate;
    private String endDate;

    private int memberCount;
    private int readyCount;
    private int failedCount;
    private int brokenCount;

    private int totalRowCount;

    private List<String> columns;
    private String columnsSignature;

    /** members 按 tsCode 升序排列；落盘前由 writer 内部排序。 */
    @Builder.Default
    private List<ManifestMember> members = new ArrayList<>();

    private long createdAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ManifestMember {
        public static final String STATUS_READY = "ready";
        public static final String STATUS_FAILED = "failed";
        public static final String STATUS_BROKEN = "broken";

        private String tsCode;
        /** 指向 atomic dataset，status=failed 时可为 null。 */
        private String datasetId;
        private String status;
        private int rowCount;
        private String startDate;
        private String endDate;
        private List<String> columns;
        /** status=failed 时填写，status=ready 时为 null。 */
        private String errorCode;
        private String errorMessage;
    }
}
