package world.willfrog.agent.model;

import java.util.List;

public class MarketNewsModels {

    private MarketNewsModels() {
    }

    public record MarketNewsQuery(
            Integer limit,
            String provider,
            String language,
            String startPublishedDate,
            String endPublishedDate
    ) {
    }

    public record MarketNewsItem(
            String id,
            String timestamp,
            String title,
            String source,
            String category,
            String url
    ) {
    }

    public record MarketNewsResult(
            List<MarketNewsItem> items,
            String provider,
            String updatedAt
    ) {
    }
}
