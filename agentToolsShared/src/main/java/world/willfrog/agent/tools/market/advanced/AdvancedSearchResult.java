package world.willfrog.agent.tools.market.advanced;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AdvancedSearchResult {
    private String tsCode;
    private String name;
    private String assetType;
    private String indexCode;
    private String indexName;
    private List<List<Object>> matchConditions = new ArrayList<>();

    public AdvancedSearchResult copy() {
        AdvancedSearchResult copy = new AdvancedSearchResult();
        copy.tsCode = tsCode;
        copy.name = name;
        copy.assetType = assetType;
        copy.indexCode = indexCode;
        copy.indexName = indexName;
        copy.matchConditions = new ArrayList<>(matchConditions);
        return copy;
    }
}
