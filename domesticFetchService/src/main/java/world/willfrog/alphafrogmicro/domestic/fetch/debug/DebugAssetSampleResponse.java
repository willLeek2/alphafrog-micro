package world.willfrog.alphafrogmicro.domestic.fetch.debug;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record DebugAssetSampleResponse(
        String status,
        @JsonProperty("requested_count") int requestedCount,
        @JsonProperty("returned_count") int returnedCount,
        @JsonProperty("candidate_count") int candidateCount,
        int attempts,
        @JsonProperty("max_attempts") int maxAttempts,
        @JsonProperty("start_date") String startDate,
        @JsonProperty("end_date") String endDate,
        @JsonProperty("ideal_daily_count") int idealDailyCount,
        @JsonProperty("required_daily_count") int requiredDailyCount,
        List<DebugAssetNameResponse> items) {

    public DebugAssetSampleResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
