package world.willfrog.agent.model;

public record MarketNewsItem(
        String id,
        String timestamp,
        String title,
        String source,
        String category,
        String url,
        String summary,
        String provider
) {
}
