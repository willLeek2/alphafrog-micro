package world.willfrog.externalinfo.ingestion.db;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 元数据查询 / 状态更新内部端点：接收 frontend 转发的请求，DB 读写。
 *
 * <p>此端点仅在 Docker 内网（alphafrog-network）可访问，18096 端口未对外暴露。
 * 鉴权由 frontend 层统一处理（AF_RAG_INGEST_TOKEN），此处直接信任来自内网的请求。
 *
 * <p>3 个端点：
 * <ul>
 *   <li>POST /rag/records/list-unprocessed —— 拉待处理记录</li>
 *   <li>POST /rag/records/mark-oss-uploaded —— 写 oss_url</li>
 *   <li>POST /rag/records/mark-vectorized —— 标 vectorized=TRUE</li>
 * </ul>
 */
@RestController
@RequestMapping("/rag/records")
@Slf4j
public class RagRecordController {

    private final RagRecordService ragRecordService;

    public RagRecordController(RagRecordService ragRecordService) {
        this.ragRecordService = ragRecordService;
    }

    @PostMapping("/list-unprocessed")
    public ResponseEntity<?> listUnprocessed(@RequestBody RagRecordListRequest request) {
        if (request == null || isBlank(request.getDocType())) {
            return ResponseEntity.badRequest().body(Map.of("error", "docType is required"));
        }
        try {
            List<Map<String, Object>> records = ragRecordService.findUnprocessed(request);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("docType", request.getDocType());
            body.put("count", records.size());
            body.put("records", records);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            log.warn("[RagRecordController] listUnprocessed bad request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[RagRecordController] listUnprocessed failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "internal error"));
        }
    }

    @PostMapping("/mark-oss-uploaded")
    public ResponseEntity<?> markOssUploaded(@RequestBody RagRecordMarkRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "request body is required"));
        }
        try {
            int affected = ragRecordService.markOssUploaded(request);
            Map<String, Object> body = new HashMap<>();
            body.put("recordId", request.getRecordId());
            body.put("affected", affected);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            log.warn("[RagRecordController] markOssUploaded bad request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[RagRecordController] markOssUploaded failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "internal error"));
        }
    }

    @PostMapping("/mark-vectorized")
    public ResponseEntity<?> markVectorized(@RequestBody RagRecordMarkRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "request body is required"));
        }
        try {
            int affected = ragRecordService.markVectorized(request);
            Map<String, Object> body = new HashMap<>();
            body.put("recordId", request.getRecordId());
            body.put("affected", affected);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            log.warn("[RagRecordController] markVectorized bad request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[RagRecordController] markVectorized failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "internal error"));
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
