package world.willfrog.alphafrogmicro.common.datasource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketReadConnectionTargetTest {

    @Test
    void verify_shouldAcceptProductionDatabaseOnRemoteHost() {
        String url = "jdbc:postgresql://10.0.0.101:5432/alphafrog";
        MarketReadConnectionTarget.verify(url);
        MarketReadConnectionTarget.Parsed parsed = MarketReadConnectionTarget.parse(url);
        assertEquals("10.0.0.101", parsed.host());
        assertEquals("alphafrog", parsed.database());
    }

    @Test
    void verify_shouldAcceptQueryString() {
        String url = "jdbc:postgresql://prod-db:5432/alphafrog?sslmode=require";
        MarketReadConnectionTarget.verify(url);
        assertEquals("prod-db", MarketReadConnectionTarget.parse(url).host());
    }

    @Test
    void verify_shouldRejectBetaDatabase() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                MarketReadConnectionTarget.verify("jdbc:postgresql://127.0.0.1:5432/alphafrog_beta"));
        assertTrue(ex.getMessage().contains("alphafrog_beta"));
    }

    @Test
    void verify_shouldRejectNonPostgresUrl() {
        assertThrows(IllegalStateException.class, () ->
                MarketReadConnectionTarget.verify("jdbc:h2:mem:alphafrog"));
    }

    @Test
    void verify_shouldRejectMissingDatabase() {
        assertThrows(IllegalStateException.class, () ->
                MarketReadConnectionTarget.verify("jdbc:postgresql://10.0.0.101:5432/"));
    }
}
