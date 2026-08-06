package world.willfrog.alphafrogmicro.frontend.controller.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import world.willfrog.alphafrogmicro.frontend.service.AdminUserAccessService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagAdminAccessControllerTest {

    @Mock
    private AdminUserAccessService adminUserAccessService;
    @Mock
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        when(adminUserAccessService.isActiveAdmin(authentication)).thenReturn(false);
    }

    @Test
    void allPublicRagFacadesRejectNonAdminJwt() {
        assertForbidden(new RagIngestController(adminUserAccessService)
                .ingest(authentication, Map.of()));
        assertForbidden(new RagUploadDocController(adminUserAccessService)
                .uploadDoc(authentication, Map.of()));

        RagRecordController records = new RagRecordController(adminUserAccessService);
        assertForbidden(records.listUnprocessed(authentication, Map.of()));
        assertForbidden(records.markOssUploaded(authentication, Map.of()));
        assertForbidden(records.markVectorized(authentication, Map.of()));

        assertForbidden(new RagFetchTriggerController(adminUserAccessService)
                .trigger(authentication, Map.of()));
    }

    private static void assertForbidden(ResponseEntity<?> response) {
        assertEquals(403, response.getStatusCode().value());
        assertEquals(Map.of("error", "Forbidden"), response.getBody());
    }
}
