package world.willfrog.alphafrogmicro.domestic.fetch.debug;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DebugAssetNameResponse(
        String tsCode,
        String name,
        @JsonProperty("full_name") String fullName) {

    public DebugAssetNameResponse(String tsCode, String name) {
        this(tsCode, name, null);
    }
}
