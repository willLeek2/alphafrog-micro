package world.willfrog.alphafrogmicro.domestic.fetch.debug;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DebugAssetNameResponse(
        String tsCode,
        String name,
        @JsonProperty("full_name") String fullName,
        @JsonProperty("daily_count") Long dailyCount,
        @JsonProperty("average_amount") Double averageAmount) {

    public DebugAssetNameResponse(String tsCode, String name) {
        this(tsCode, name, null, null, null);
    }

    public DebugAssetNameResponse(String tsCode, String name, String fullName) {
        this(tsCode, name, fullName, null, null);
    }
}
