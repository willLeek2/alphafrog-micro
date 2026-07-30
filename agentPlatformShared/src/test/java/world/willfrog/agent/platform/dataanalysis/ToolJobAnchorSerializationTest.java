package world.willfrog.agent.platform.dataanalysis;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ToolJobAnchorSerializationTest {

    @Test
    void writesLeaseAsExactIsoStringAndRoundTrips() {
        Instant lease = Instant.parse("2026-07-30T07:45:47.335223Z");
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setBlockingLeaseUntil(lease);

        String json = anchor.toJson();

        assertThat(json).contains(
                "\"blockingLeaseUntil\":\"2026-07-30T07:45:47.335223Z\"");
        assertThat(ToolJobAnchor.fromJson(json).getBlockingLeaseUntil())
                .isEqualTo(lease);
    }

    @Test
    void readsHistoricalNumericAndIsoLeaseRepresentations() {
        ToolJobAnchor numeric = ToolJobAnchor.fromJson(
                "{\"blockingLeaseUntil\":1785397547.335223000}");
        ToolJobAnchor textual = ToolJobAnchor.fromJson(
                "{\"blockingLeaseUntil\":\"2026-07-30T07:45:47.335223Z\"}");

        assertThat(numeric.getBlockingLeaseUntil())
                .isEqualTo(Instant.parse("2026-07-30T07:45:47.335223Z"));
        assertThat(textual.getBlockingLeaseUntil())
                .isEqualTo(Instant.parse("2026-07-30T07:45:47.335223Z"));
    }
}
