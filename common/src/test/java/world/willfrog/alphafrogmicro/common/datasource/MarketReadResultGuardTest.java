package world.willfrog.alphafrogmicro.common.datasource;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketReadResultGuardTest {

    private MarketReadResultGuard guardWith(int maxRows, long maxBytes, int defaultPageSize, int maxPageSize) {
        MarketReadDataSourceProperties.Limits limits = new MarketReadDataSourceProperties.Limits();
        limits.setMaxRows(maxRows);
        limits.setMaxBytes(maxBytes);
        limits.setDefaultPageSize(defaultPageSize);
        limits.setMaxPageSize(maxPageSize);
        return new MarketReadResultGuard(limits);
    }















}
