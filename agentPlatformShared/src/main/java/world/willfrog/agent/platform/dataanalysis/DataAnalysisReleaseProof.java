package world.willfrog.agent.platform.dataanalysis;

/** Release evidence is explicit: task-bound reservations need a durable terminal envelope. */
public sealed interface DataAnalysisReleaseProof
        permits DataAnalysisReleaseProof.Terminal, DataAnalysisReleaseProof.PreDispatchAbort {

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
}
