package world.willfrog.agent.tools.registry;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 平台工具单一声明源（26Q3 Stage1 D05）。
 *
 * <p>可路由工具集、运行时 LC4j 目录、对外 API 目录、压缩/缓存/并行上限等能力白名单，
 * 全部由本注册表派生或经契约测试对照校验；新增工具在此登记一次，漏登记派生面会在
 * 契约测试失败，不再依赖多处手工同步。</p>
 *
 * <p>不变量「声明 ⊆ 可路由」：本注册表出现的每个名字都必须在 {@code ToolRouter}
 * 有可执行分发（能力关闭返回 {@code CAPABILITY_DISABLED} 属已实现语义，允许；
 * 仅因未实现而 {@code UNSUPPORTED_TOOL} 不允许）。{@code spawnSubAgent} /
 * {@code waitForSubAgent} 由 D06 的 {@code SubAgentControlHandler} 提供真实路由，
 * 与声明面同生同灭。</p>
 *
 * <p>本表只承载「声明与能力归属」真相；限流拒绝是否计费、匿名缓存隔离等运行时治理
 * 口径归 D07，不在本表展开。</p>
 */
public final class AgentToolRegistry {

    /** 工具域。 */
    public enum Domain {
        MARKET_DATA, RAG, WEB_SEARCH, PYTHON_SANDBOX, FINANCE, DOCS, DATASET, COMPACTION, META
    }

    /** 能力开关门控。NONE=常开；WEB_SEARCH/CODE_INTERPRETER 在目录构建期过滤；ADJ_FACTOR 在执行期校验。 */
    public enum CapabilityGate {
        NONE, WEB_SEARCH, CODE_INTERPRETER, ADJ_FACTOR
    }

    /** 压缩（截断/摘要/rawRef）资格。EXEMPT 必须带可审查理由。 */
    public enum Compression {
        ELIGIBLE, EXCLUDED, EXEMPT
    }

    /** 工具结果缓存族（对齐 ToolResultCacheService.resolveMode 的 SEARCH/INFO→REDIS、DATASET→DATASET_REGISTRY）。 */
    public enum CacheFamily {
        NONE, SEARCH, INFO, DATASET
    }

    /** checkParallelLimits 响应中的并行上限说明组（与 MarketDataTools 响应组名单对照校验）。 */
    public enum ParallelGroup {
        SEARCH, DAILY, CALENDAR, ADVANCED
    }

    /** 批量权重计数的参数键族（与 ToolWeightedLimitService.countBatchItems 分支对照校验；文件只读，故仅登记族别）。 */
    public enum BatchCountKeys {
        NONE, QUERY, TS_CODE, DATES
    }

    /** canonical 规格覆盖来源。NONE=Bean 反射；其余为各 catalog helper 或手工构建。 */
    public enum CanonicalSpec {
        NONE, MARKET_ADVANCED, PARALLEL_LIMITS, MANUAL_FINANCE, MANUAL_SUB_AGENT
    }

    /**
     * 单条工具声明。{@code compressionExemptionReason} 仅当 compression=EXEMPT 时必填，
     * 其余取值必须为 null，防止「静默缺席」与「理由泛滥」两种漂移。
     */
    public record ToolDeclaration(
            String name,
            Domain domain,
            CapabilityGate capabilityGate,
            Compression compression,
            String compressionExemptionReason,
            CacheFamily cacheFamily,
            Set<ParallelGroup> parallelGroups,
            BatchCountKeys batchCountKeys,
            CanonicalSpec canonicalSpec,
            String beanClassName) {

        public ToolDeclaration {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("tool name must not be blank");
            }
            if (compression == Compression.EXEMPT
                    && (compressionExemptionReason == null || compressionExemptionReason.isBlank())) {
                throw new IllegalArgumentException("compression EXEMPT requires a reviewable reason: " + name);
            }
            if (compression != Compression.EXEMPT && compressionExemptionReason != null) {
                throw new IllegalArgumentException("compressionExemptionReason only allowed for EXEMPT: " + name);
            }
            parallelGroups = parallelGroups == null ? Set.of() : Set.copyOf(parallelGroups);
        }
    }

    private static final List<ToolDeclaration> DECLARATIONS = List.of(
            new ToolDeclaration("getStockInfo", Domain.MARKET_DATA, CapabilityGate.NONE,
                    Compression.ELIGIBLE, null, CacheFamily.INFO,
                    Set.of(ParallelGroup.SEARCH), BatchCountKeys.NONE, CanonicalSpec.NONE, "MarketDataTools"),
            new ToolDeclaration("getStockDaily", Domain.MARKET_DATA, CapabilityGate.NONE,
                    Compression.ELIGIBLE, null, CacheFamily.DATASET,
                    Set.of(ParallelGroup.DAILY), BatchCountKeys.TS_CODE, CanonicalSpec.NONE, "MarketDataTools"),
            new ToolDeclaration("getStockSwIndustryInfo", Domain.MARKET_DATA, CapabilityGate.NONE,
                    Compression.EXEMPT, "已路由但历史未纳入压缩白名单（S3A-005 漂移样本）；D05 登记现状、行为不变，压缩资格评估留待后续",
                    CacheFamily.NONE, Set.of(ParallelGroup.SEARCH), BatchCountKeys.NONE, CanonicalSpec.NONE, "MarketDataTools"),
            new ToolDeclaration("searchStock", Domain.MARKET_DATA, CapabilityGate.NONE,
                    Compression.ELIGIBLE, null, CacheFamily.SEARCH,
                    Set.of(ParallelGroup.SEARCH), BatchCountKeys.QUERY, CanonicalSpec.NONE, "MarketDataTools"),
            new ToolDeclaration("searchFund", Domain.MARKET_DATA, CapabilityGate.NONE,
                    Compression.ELIGIBLE, null, CacheFamily.SEARCH,
                    Set.of(ParallelGroup.SEARCH), BatchCountKeys.QUERY, CanonicalSpec.NONE, "MarketDataTools"),
            new ToolDeclaration("getIndexInfo", Domain.MARKET_DATA, CapabilityGate.NONE,
                    Compression.ELIGIBLE, null, CacheFamily.INFO,
                    Set.of(ParallelGroup.SEARCH), BatchCountKeys.NONE, CanonicalSpec.NONE, "MarketDataTools"),
            new ToolDeclaration("getIndexDaily", Domain.MARKET_DATA, CapabilityGate.NONE,
                    Compression.ELIGIBLE, null, CacheFamily.DATASET,
                    Set.of(ParallelGroup.DAILY), BatchCountKeys.TS_CODE, CanonicalSpec.NONE, "MarketDataTools"),
            new ToolDeclaration("searchIndex", Domain.MARKET_DATA, CapabilityGate.NONE,
                    Compression.ELIGIBLE, null, CacheFamily.SEARCH,
                    Set.of(ParallelGroup.SEARCH, ParallelGroup.ADVANCED), BatchCountKeys.QUERY,
                    CanonicalSpec.MARKET_ADVANCED, "MarketDataTools"),
            new ToolDeclaration("searchAssetInfo", Domain.MARKET_DATA, CapabilityGate.NONE,
                    Compression.ELIGIBLE, null, CacheFamily.SEARCH,
                    Set.of(ParallelGroup.SEARCH, ParallelGroup.ADVANCED), BatchCountKeys.QUERY,
                    CanonicalSpec.MARKET_ADVANCED, "MarketDataTools"),
            new ToolDeclaration("checkParallelLimits", Domain.META, CapabilityGate.NONE,
                    Compression.EXEMPT, "元工具，响应体为限流说明且体量小，不进入压缩机制（现状如此）",
                    CacheFamily.NONE, Set.of(), BatchCountKeys.NONE, CanonicalSpec.PARALLEL_LIMITS, "MarketDataTools"),
            new ToolDeclaration("getTradingDaysSummary", Domain.MARKET_DATA, CapabilityGate.NONE,
                    Compression.ELIGIBLE, null, CacheFamily.NONE,
                    Set.of(), BatchCountKeys.NONE, CanonicalSpec.NONE, "MarketDataTools"),
            new ToolDeclaration("isTradingDay", Domain.MARKET_DATA, CapabilityGate.NONE,
                    Compression.ELIGIBLE, null, CacheFamily.NONE,
                    Set.of(ParallelGroup.CALENDAR), BatchCountKeys.DATES, CanonicalSpec.NONE, "MarketDataTools"),
            new ToolDeclaration("getExchangeAssetDaily", Domain.MARKET_DATA, CapabilityGate.NONE,
                    Compression.ELIGIBLE, null, CacheFamily.DATASET,
                    Set.of(ParallelGroup.DAILY, ParallelGroup.ADVANCED), BatchCountKeys.TS_CODE,
                    CanonicalSpec.MARKET_ADVANCED, "MarketDataTools"),
            new ToolDeclaration("getOffExchangeAssetDaily", Domain.MARKET_DATA, CapabilityGate.NONE,
                    Compression.ELIGIBLE, null, CacheFamily.DATASET,
                    Set.of(), BatchCountKeys.NONE, CanonicalSpec.NONE, "MarketDataTools"),
            new ToolDeclaration("getEtfAdj", Domain.MARKET_DATA, CapabilityGate.ADJ_FACTOR,
                    Compression.ELIGIBLE, null, CacheFamily.DATASET,
                    Set.of(), BatchCountKeys.NONE, CanonicalSpec.NONE, "MarketDataTools"),
            new ToolDeclaration("getListedAssetShareSize", Domain.MARKET_DATA, CapabilityGate.NONE,
                    Compression.ELIGIBLE, null, CacheFamily.DATASET,
                    Set.of(), BatchCountKeys.NONE, CanonicalSpec.NONE, "MarketDataTools"),
            new ToolDeclaration("getFinancialReport", Domain.MARKET_DATA, CapabilityGate.NONE,
                    Compression.ELIGIBLE, null, CacheFamily.NONE,
                    Set.of(), BatchCountKeys.NONE, CanonicalSpec.NONE, "MarketDataTools"),
            new ToolDeclaration("ragSearch", Domain.RAG, CapabilityGate.NONE,
                    Compression.ELIGIBLE, null, CacheFamily.NONE,
                    Set.of(), BatchCountKeys.NONE, CanonicalSpec.NONE, "RagTools"),
            new ToolDeclaration("loadDocument", Domain.RAG, CapabilityGate.NONE,
                    Compression.ELIGIBLE, null, CacheFamily.NONE,
                    Set.of(), BatchCountKeys.NONE, CanonicalSpec.NONE, "RagTools"),
            new ToolDeclaration("searchWeb", Domain.WEB_SEARCH, CapabilityGate.WEB_SEARCH,
                    Compression.EXCLUDED, null, CacheFamily.NONE,
                    Set.of(), BatchCountKeys.NONE, CanonicalSpec.NONE, "SearchTools"),
            new ToolDeclaration("executePython", Domain.PYTHON_SANDBOX, CapabilityGate.CODE_INTERPRETER,
                    Compression.EXCLUDED, null, CacheFamily.NONE,
                    Set.of(), BatchCountKeys.NONE, CanonicalSpec.NONE, "PythonSandboxTools"),
            new ToolDeclaration("resolveFinanceMethods", Domain.FINANCE, CapabilityGate.NONE,
                    Compression.EXEMPT, "返回方法建议卡/结构化清单，体量小；现状未纳入压缩白名单",
                    CacheFamily.NONE, Set.of(), BatchCountKeys.NONE, CanonicalSpec.MANUAL_FINANCE, "FinanceMethodTools"),
            new ToolDeclaration("loadToolGuide", Domain.DOCS, CapabilityGate.NONE,
                    Compression.EXEMPT, "指南正文按需加载；现状未纳入压缩白名单（D05 登记，资格评估留后续）",
                    CacheFamily.NONE, Set.of(), BatchCountKeys.NONE, CanonicalSpec.NONE, "LoadToolGuideTool"),
            new ToolDeclaration("rereadToolResult", Domain.COMPACTION, CapabilityGate.NONE,
                    Compression.EXEMPT, "压缩机制的原始结果回读端，自身输出不应再被压缩（语义豁免）",
                    CacheFamily.NONE, Set.of(), BatchCountKeys.NONE, CanonicalSpec.NONE, "RereadToolHandler"),
            new ToolDeclaration("listMyData", Domain.DATASET, CapabilityGate.NONE,
                    Compression.EXEMPT, "run 级数据集/清单注册查询，响应体量小；现状未纳入压缩白名单",
                    CacheFamily.NONE, Set.of(), BatchCountKeys.NONE, CanonicalSpec.NONE, "ListMyDataTool"),
            new ToolDeclaration("spawnSubAgent", Domain.META, CapabilityGate.NONE,
                    Compression.EXCLUDED, null, CacheFamily.NONE,
                    Set.of(), BatchCountKeys.NONE, CanonicalSpec.MANUAL_SUB_AGENT, "SubAgentControlHandler"),
            new ToolDeclaration("waitForSubAgent", Domain.META, CapabilityGate.NONE,
                    Compression.EXCLUDED, null, CacheFamily.NONE,
                    Set.of(), BatchCountKeys.NONE, CanonicalSpec.MANUAL_SUB_AGENT, "SubAgentControlHandler")
    );

    private static final Map<String, ToolDeclaration> BY_NAME;

    static {
        Map<String, ToolDeclaration> byName = new LinkedHashMap<>();
        for (ToolDeclaration declaration : DECLARATIONS) {
            if (byName.put(declaration.name(), declaration) != null) {
                throw new IllegalStateException("duplicate tool declaration: " + declaration.name());
            }
        }
        BY_NAME = Collections.unmodifiableMap(byName);
    }

    private AgentToolRegistry() {
    }

    /** 全部声明（登记顺序）。 */
    public static Collection<ToolDeclaration> all() {
        return DECLARATIONS;
    }

    /** 生产声明面工具名集合（登记顺序的不可变副本）。 */
    public static Set<String> declaredToolNames() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(BY_NAME.keySet()));
    }

    /** 按名查声明；未登记返回 empty。 */
    public static Optional<ToolDeclaration> find(String name) {
        return Optional.ofNullable(BY_NAME.get(name));
    }

    /** 按名取声明；未登记直接失败（fail-closed）。 */
    public static ToolDeclaration require(String name) {
        ToolDeclaration declaration = BY_NAME.get(name);
        if (declaration == null) {
            throw new IllegalArgumentException("tool not declared in AgentToolRegistry: " + name);
        }
        return declaration;
    }

    /** 指定压缩资格的工具名集合。 */
    public static Set<String> namesWithCompression(Compression compression) {
        Set<String> names = new LinkedHashSet<>();
        for (ToolDeclaration declaration : DECLARATIONS) {
            if (declaration.compression() == compression) {
                names.add(declaration.name());
            }
        }
        return Collections.unmodifiableSet(names);
    }

    /** 指定缓存族的工具名集合。 */
    public static Set<String> namesInCacheFamily(CacheFamily family) {
        Set<String> names = new LinkedHashSet<>();
        for (ToolDeclaration declaration : DECLARATIONS) {
            if (declaration.cacheFamily() == family) {
                names.add(declaration.name());
            }
        }
        return Collections.unmodifiableSet(names);
    }

    /** 指定并行说明组的工具名集合。 */
    public static Set<String> namesInParallelGroup(ParallelGroup group) {
        Set<String> names = new LinkedHashSet<>();
        for (ToolDeclaration declaration : DECLARATIONS) {
            if (declaration.parallelGroups().contains(group)) {
                names.add(declaration.name());
            }
        }
        return Collections.unmodifiableSet(names);
    }

    /** 指定批量计数键族的工具名集合。 */
    public static Set<String> namesWithBatchCountKeys(BatchCountKeys keys) {
        Set<String> names = new LinkedHashSet<>();
        for (ToolDeclaration declaration : DECLARATIONS) {
            if (declaration.batchCountKeys() == keys) {
                names.add(declaration.name());
            }
        }
        return Collections.unmodifiableSet(names);
    }

    /** 指定 canonical 覆盖来源的工具名集合。 */
    public static Set<String> namesWithCanonicalSpec(CanonicalSpec canonicalSpec) {
        Set<String> names = new LinkedHashSet<>();
        for (ToolDeclaration declaration : DECLARATIONS) {
            if (declaration.canonicalSpec() == canonicalSpec) {
                names.add(declaration.name());
            }
        }
        return Collections.unmodifiableSet(names);
    }

    /** 指定能力门控的工具名集合。 */
    public static Set<String> namesWithCapabilityGate(CapabilityGate gate) {
        Set<String> names = new LinkedHashSet<>();
        for (ToolDeclaration declaration : DECLARATIONS) {
            if (declaration.capabilityGate() == gate) {
                names.add(declaration.name());
            }
        }
        return Collections.unmodifiableSet(names);
    }
}
