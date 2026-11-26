package world.willfrog.agent.platform.experiment;

import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.racing.RacingCallException;
import world.willfrog.alphafrogmicro.common.racing.RacingClientSettings;
import world.willfrog.alphafrogmicro.common.racing.RacingTransport;
import world.willfrog.racing.idl.ConfirmRuntimeContextReply;
import world.willfrog.racing.idl.ConfirmRuntimeContextRequest;
import world.willfrog.racing.idl.RootIdentity;
import world.willfrog.racing.idl.RuntimeCallContext;
import world.willfrog.racing.idl.RuntimeIdentity;

/** 新根冻结可信绑定，重启和子运行沿持久关系读取原绑定，普通发布只刷新本次代际。 */
@Component
public final class ExperimentRunContextFactory {
    private final RacingClientSettings settings;
    private final RacingTransport transport;
    private final ExperimentRunBindingStore bindings;

    public ExperimentRunContextFactory(RacingClientSettings settings, RacingTransport transport,
            ExperimentRunBindingStore bindings) {
        this.settings = settings;
        this.transport = transport;
        this.bindings = bindings;
    }

    public RuntimeCallContext freeze(String rootRunId) {
        if (rootRunId == null || rootRunId.isBlank()) {
            throw RacingCallException.failure("INVALID_ARGUMENT", "冻结实验上下文缺少根运行编号");
        }
        RuntimeCallContext proposed = RuntimeCallContext.newBuilder().setTarget(settings.target())
                .setRoot(RootIdentity.newBuilder().setOwnerNamespace("alphafrog").setOwnerKind("agent_run")
                        .setRootId(rootRunId).setOwnerScopeInstanceId(settings.ownScopeInstanceId())
                        .setOwnerRouteTag(settings.ownRouteTag()))
                .setCurrentRunId(rootRunId).setOperationId("prepare:" + rootRunId)
                .setCallerGenerationId(settings.callerGenerationId()).build();
        return confirm(proposed);
    }

    public RuntimeCallContext current(String runId) {
        return current(runId, "prepare:" + runId);
    }

    public RuntimeCallContext current(String runId, String operationId) {
        RuntimeCallContext saved = bindings.saved(runId);
        if (!settings.ownScopeInstanceId().equals(saved.getRoot().getOwnerScopeInstanceId())
                || !settings.ownRouteTag().equals(saved.getRoot().getOwnerRouteTag())) {
            throw RacingCallException.failure("SCOPE_MISMATCH", "当前执行实例不属于根运行保存的原范围");
        }
        return confirm(saved.toBuilder().setCurrentRunId(runId).setOperationId(operationId).build());
    }

    private RuntimeCallContext confirm(RuntimeCallContext proposed) {
        ConfirmRuntimeContextReply reply = transport.call(proposed,
                (service, current) -> {
                    ConfirmRuntimeContextReply response = service.confirmRuntimeContext(ConfirmRuntimeContextRequest.newBuilder()
                            .setAction("ResolvePromptSelection").setContext(current).build());
                    if (response.hasConfirmed() && !current.equals(response.getConfirmed().getContext())) {
                        throw RacingCallException.failure("SCOPE_MISMATCH", "平台确认回复改变了请求上下文");
                    }
                    return response;
                },
                ExperimentRunContextFactory::confirmedIdentity);
        RuntimeCallContext confirmed = reply.getConfirmed().getContext();
        ExperimentRunBindingStore.requireSameBinding(proposed, confirmed);
        if (!proposed.getCurrentRunId().equals(confirmed.getCurrentRunId())
                || !proposed.getOperationId().equals(confirmed.getOperationId())) {
            throw RacingCallException.failure("SCOPE_MISMATCH", "平台确认回复改变了当前执行定位");
        }
        return confirmed;
    }

    private static RuntimeIdentity confirmedIdentity(ConfirmRuntimeContextReply reply) {
        if (reply.hasError()) throw new RacingCallException(reply.getError());
        if (!reply.hasConfirmed() || !reply.getConfirmed().hasContext()
                || !reply.getConfirmed().hasActualTarget() || reply.getConfirmed().getServicePrincipal().isBlank()) {
            throw RacingCallException.failure("SCOPE_UNAVAILABLE", "平台确认回复缺少上下文或实际执行身份");
        }
        return reply.getConfirmed().getActualTarget();
    }
}
