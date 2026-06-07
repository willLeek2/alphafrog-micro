package world.willfrog.alphafrogmicro.frontend.controller.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * RAG 元数据查询 / 状态更新公网侧入口。
 *
 * <p>校验客户端 Bearer token（AF_RAG_INGEST_TOKEN），通过后将请求体
 * 原样转发给 externalInfoService 内部 HTTP 端点，由其负责 DB 读写。
 *
 * <p>3 个转发端点（路径与外部接口完全对齐）：
 * <ul>
 *   <li>POST /rag/records/list-unprocessed</li>
 *   <li>POST /rag/records/mark-oss-uploaded</li>
 *   <li>POST /rag/records/mark-vectorized</li>
 * </ul>
 */
@RestController
@RequestMapping("/rag/records")
@Slf4j
public class RagRecordController {

    @Value("${alphafrog.rag.ingest.admin-token:}")
    private String adminToken;

    @Value("${alphafrog.rag.ingest.external-info-service-url:http://alphafrog-external-info-service:18096}")
    private String externalInfoServiceUrl;

    private final RestTemplate restTemplate = buildRestTemplate();

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(25_000);
        return new RestTemplate(factory);
    }

    @PostMapping("/list-unprocessed")
    public ResponseEntity<?> listUnprocessed(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body) {
        return forward(authHeader, body, "/list-unprocessed", "listUnprocessed");
    }

    @PostMapping("/mark-oss-uploaded")
    public ResponseEntity<?> markOssUploaded(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body) {
        return forward(authHeader, body, "/mark-oss-uploaded", "markOssUploaded");
    }

    @PostMapping("/mark-vectorized")
    public ResponseEntity<?> markVectorized(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body) {
        return forward(authHeader, body, "/mark-vectorized", "markVectorized");
    }

    private ResponseEntity<?> forward(String authHeader, Map<String, Object> body,
                                      String path, String opName) {
        if (!isAuthorized(authHeader)) {
            log.warn("[RagRecordController] Unauthorized {} attempt", opName);
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String forwardUrl = externalInfoServiceUrl.stripTrailing() + "/rag/records" + path;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(forwardUrl, entity, Map.class);
            log.info("[RagRecordController] Forwarded {}, upstream status={}",
                    opName, resp.getStatusCode());
            return ResponseEntity.status(resp.getStatusCode()).body(resp.getBody());
        } catch (Exception e) {
            log.error("[RagRecordController] Failed to forward {} to {}: {}",
                    opName, forwardUrl, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to reach externalInfoService"));
        }
    }

    private boolean isAuthorized(String authHeader) {
        if (adminToken == null || adminToken.isBlank()) {
            return true;
        }
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return false;
        }
        return adminToken.equals(authHeader.substring(7));
    }
}
