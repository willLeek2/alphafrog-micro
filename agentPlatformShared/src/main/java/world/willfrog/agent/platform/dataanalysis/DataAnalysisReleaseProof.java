package world.willfrog.agent.platform.dataanalysis;

import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;

/** Release evidence is explicit: task-bound reservations need a durable terminal envelope. */
public sealed interface DataAnalysisReleaseProof
        permits DataAnalysisReleaseProof.Terminal, DataAnalysisReleaseProof.PreDispatchAbort,
                DataAnalysisReleaseProof.WorkspaceRefusal {

    record Terminal(DataAnalysisTerminalEnvelope envelope) implements DataAnalysisReleaseProof {
        public Terminal {
            if (envelope == null) {
                throw new IllegalArgumentException("envelope must not be null");
            }
        }
    }

    record PreDispatchAbort(DataAnalysisOperationIdentity identity) implements DataAnalysisReleaseProof {
        public PreDispatchAbort {
            if (identity == null) {
                throw new IllegalArgumentException("identity must not be null");
            }
        }
    }

    /** 持久工作区明确拒绝且原操作号权威无任务；无任务可供终态结算。 */
    record WorkspaceRefusal(DataAnalysisOperationIdentity identity, String code)
            implements DataAnalysisReleaseProof {
        public WorkspaceRefusal {
            if (identity == null || !WaitMemberDispatchProof.isWorkspaceRefusalCode(code)) {
                throw new IllegalArgumentException("workspace refusal identity and code are required");
            }
        }
    }
}
