package world.willfrog.agent.platform.dataanalysis;

public enum DataAnalysisResourceClass {
    STANDARD(1),
    HEAVY(3);

    private final int defaultCapacityUnits;

    DataAnalysisResourceClass(int defaultCapacityUnits) {
        this.defaultCapacityUnits = defaultCapacityUnits;
    }

    public int defaultCapacityUnits() {
        return defaultCapacityUnits;
    }
}
