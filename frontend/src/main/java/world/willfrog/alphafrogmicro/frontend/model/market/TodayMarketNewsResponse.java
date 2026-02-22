package world.willfrog.alphafrogmicro.frontend.model.market;

import java.util.List;

public record TodayMarketNewsResponse(
        List<MarketNewsItemResponse> data,
        String updatedAt,
        String provider
) {
}
