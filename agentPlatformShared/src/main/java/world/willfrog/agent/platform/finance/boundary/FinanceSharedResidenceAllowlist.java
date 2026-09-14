package world.willfrog.agent.platform.finance.boundary;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * D22 §4.1 金融运行时边界：允许驻留 agentPlatformShared 的金融类封闭清单。
 *
 * <p>背景：D22 在「外迁接缝」与「明确允许驻留清单」之间选定后者——清单是
 * <b>封闭集合</b>，逐项精确 FQCN，不构成「同类可参照进入」的先例。</p>
 *
 * <h3>扩张规则（禁止事项）</h3>
 * <ol>
 *   <li>任何新的金融类（简单名以 {@code Finance} 开头，或位于 {@code *.finance.*} 包）
 *       默认禁止进入本模块；</li>
 *   <li>确需进入的例外，必须先修订边界文档
 *       {@code agentPlatformShared/docs/d22-finance-residence-allowlist-v1.md}
 *       并同步更新本清单与架构测试预期——即需要 D22 边界文档的 parent 版本审批；</li>
 *   <li>命名约定是本清单可机械执行的前提：新金融类必须遵守上述命名/包名约定，
 *       否则架构测试无法识别，评审环节必须人工拦截。</li>
 * </ol>
 *
 * <p>本类自身属于边界治理工件，按同一规则列入清单。</p>
 */
public final class FinanceSharedResidenceAllowlist {

    private FinanceSharedResidenceAllowlist() {
    }

    /**
     * 允许驻留的精确 FQCN 集合（封闭、排序、不可变）。
     *
     * @return 当前允许驻留 agentPlatformShared 的全部金融类精确 FQCN
     */
    public static Set<String> allowedFinanceClasses() {
        return ALLOWED;
    }

    private static final Set<String> ALLOWED = Collections.unmodifiableSet(new LinkedHashSet<>(Set.of(
            "world.willfrog.agent.platform.finance.FinanceEnvironmentFact",
            "world.willfrog.agent.platform.finance.FinanceEnvironmentVerifier",
            "world.willfrog.agent.platform.finance.FinanceEvidenceLevel",
            "world.willfrog.agent.platform.finance.FinanceMethodResolution",
            "world.willfrog.agent.platform.finance.FinanceMethodResolutionPersistenceSink",
            "world.willfrog.agent.platform.finance.FinanceMethodResolutionPersister",
            "world.willfrog.agent.platform.finance.FinanceMethodResolutionQuery",
            "world.willfrog.agent.platform.finance.FinanceMethodResolutionSink",
            "world.willfrog.agent.platform.finance.FinanceMethodResolutionSinkException",
            "world.willfrog.agent.platform.finance.FinanceMethodResolutionSnapshot",
            "world.willfrog.agent.platform.finance.FinanceMethodResolverClient",
            "world.willfrog.agent.platform.finance.FinanceMetricRecord",
            "world.willfrog.agent.platform.finance.FinanceRecordBatch",
            "world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader",
            "world.willfrog.agent.platform.finance.FinanceRecordChannelLimits",
            "world.willfrog.agent.platform.finance.FinanceRecordChannelMetadata",
            "world.willfrog.agent.platform.finance.FinanceRecordChannelObservability",
            "world.willfrog.agent.platform.finance.FinanceRecordChannelProcessor",
            "world.willfrog.agent.platform.finance.FinanceRecordChannelProperties",
            "world.willfrog.agent.platform.finance.FinanceRecordDecoder",
            "world.willfrog.agent.platform.finance.FinanceRecordExtractionRequest",
            "world.willfrog.agent.platform.finance.FinanceRecordExtractionResult",
            "world.willfrog.agent.platform.finance.FinanceRecordPersister",
            "world.willfrog.agent.platform.finance.FinanceRecordProcessingException",
            "world.willfrog.agent.platform.finance.FinanceRecordQuery",
            "world.willfrog.agent.platform.finance.FinanceRecordSchemaValidator",
            "world.willfrog.agent.platform.finance.FinanceToolResultFormatter",
            "world.willfrog.agent.platform.finance.boundary.FinanceSharedResidenceAllowlist",
            "world.willfrog.agent.platform.mapper.FinanceMethodResolutionMapper",
            "world.willfrog.agent.platform.mapper.FinanceMetricRecordMapper",
            "world.willfrog.agent.platform.mapper.FinanceRecordBatchMapper",
            "world.willfrog.agent.platform.service.FinanceMethodResolverModelResolver",
            "world.willfrog.agent.platform.service.FinanceMethodResolverModelService"
    )));
}
