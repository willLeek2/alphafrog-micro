package world.willfrog.alphafrogmicro.frontend.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.client.HttpClientErrorException;
import world.willfrog.alphafrogmicro.frontend.service.AdminUserAccessService;
import world.willfrog.alphafrogmicro.frontend.service.debug.DomesticMarketSampleClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminMarketSampleControllerTest {

    @Mock
    private AdminUserAccessService adminUserAccessService;
    @Mock
    private DomesticMarketSampleClient marketSampleClient;

    private AdminMarketSampleController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminMarketSampleController(adminUserAccessService, marketSampleClient);
    }

    @Test
    void adminAccountCanFetchRandomIndexNames() {
        Authentication authentication = authentication("admin");
        when(adminUserAccessService.isActiveAdmin(authentication)).thenReturn(true);
        when(marketSampleClient.randomIndexNamesByAmount(
                2021, 2025, 100000.0, 7, 3, 2))
                .thenReturn(Map.of(
                        "status", "complete",
                        "items", List.of(Map.of("tsCode", "000300.SH", "name", "沪深300"))));

        ResponseEntity<?> response = controller.randomIndexNamesByAmount(
                authentication, 2021, 2025, 100000.0, 7, 3, 2);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Map.of(
                "status", "complete",
                "items", List.of(Map.of("tsCode", "000300.SH", "name", "沪深300"))),
                response.getBody());
    }

    @Test
    void adminAccountCanFetchRandomStocksAndEtfs() {
        Authentication authentication = authentication("admin-assets");
        when(adminUserAccessService.isActiveAdmin(authentication)).thenReturn(true);
        when(marketSampleClient.randomListedStocks(2021, 2025, null, 7, 3, 1))
                .thenReturn(Map.of("status", "complete", "items",
                        List.of(Map.of("tsCode", "600519.SH", "name", "贵州茅台"))));
        when(marketSampleClient.randomListedEtfs(2021, 2025, null, 7, 3, 1))
                .thenReturn(Map.of("status", "complete", "items",
                        List.of(Map.of("tsCode", "510300.SH", "name", "沪深300ETF"))));

        ResponseEntity<?> stocks = controller.randomListedStocks(
                authentication, 2021, 2025, null, 7, 3, 1);
        ResponseEntity<?> etfs = controller.randomListedEtfs(
                authentication, 2021, 2025, null, 7, 3, 1);

        assertEquals(200, stocks.getStatusCode().value());
        assertEquals(200, etfs.getStatusCode().value());
        assertEquals(Map.of("status", "complete", "items",
                List.of(Map.of("tsCode", "600519.SH", "name", "贵州茅台"))), stocks.getBody());
        assertEquals(Map.of("status", "complete", "items",
                List.of(Map.of("tsCode", "510300.SH", "name", "沪深300ETF"))), etfs.getBody());
    }

    @Test
    void zeroEligibleAssetsShouldRemainUnprocessableEntity() {
        Authentication authentication = authentication("admin-zero-assets");
        when(adminUserAccessService.isActiveAdmin(authentication)).thenReturn(true);
        when(marketSampleClient.randomListedStocks(2021, 2025, null, 7, 3, 1))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNPROCESSABLE_ENTITY));

        ResponseEntity<?> response = controller.randomListedStocks(
                authentication, 2021, 2025, null, 7, 3, 1);

        assertEquals(422, response.getStatusCode().value());
        assertEquals(Map.of("error", "Random market sample request was rejected"),
                response.getBody());
    }

    @Test
    void normalAccountIsRejectedBeforeCallingUpstream() {
        Authentication authentication = authentication("normal");
        when(adminUserAccessService.isActiveAdmin(authentication)).thenReturn(false);

        ResponseEntity<?> response = controller.randomSwL3Industries(authentication, 2);

        assertEquals(403, response.getStatusCode().value());
        verify(marketSampleClient, never()).randomSwL3Industries(2);
    }

    @Test
    void missingAuthenticationIsRejectedBeforeCallingUpstream() {
        ResponseEntity<?> response = controller.randomSwL3Industries(null, 2);

        assertEquals(403, response.getStatusCode().value());
        verify(marketSampleClient, never()).randomSwL3Industries(2);
    }

    @Test
    void disabledAdminAccountIsRejected() {
        Authentication authentication = authentication("disabled-admin");
        when(adminUserAccessService.isActiveAdmin(authentication)).thenReturn(false);

        ResponseEntity<?> response = controller.randomSwL3Industries(authentication, 1);

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void adminAccountWithoutExplicitStatusIsRejected() {
        Authentication authentication = authentication("statusless-admin");
        when(adminUserAccessService.isActiveAdmin(authentication)).thenReturn(false);

        ResponseEntity<?> response = controller.randomSwL3Industries(authentication, 1);

        assertEquals(403, response.getStatusCode().value());
        verify(marketSampleClient, never()).randomSwL3Industries(1);
    }

    @Test
    void adminAccountWithBlankStatusIsRejected() {
        Authentication authentication = authentication("blank-status-admin");
        when(adminUserAccessService.isActiveAdmin(authentication)).thenReturn(false);

        ResponseEntity<?> response = controller.randomSwL3Industries(authentication, 1);

        assertEquals(403, response.getStatusCode().value());
        verify(marketSampleClient, never()).randomSwL3Industries(1);
    }

    private Authentication authentication(String username) {
        return mock(Authentication.class, username);
    }

}
