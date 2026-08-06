package world.willfrog.agent.platform.dataanalysis;

@FunctionalInterface
public interface DataAnalysisTerminalRecorder {

    DataAnalysisUpsertOutcome upsert(DataAnalysisTerminalEnvelope envelope);
}
