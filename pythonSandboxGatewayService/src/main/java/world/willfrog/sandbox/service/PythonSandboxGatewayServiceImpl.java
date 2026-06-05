package world.willfrog.sandbox.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import java.util.List;

@DubboService
@Slf4j
public class PythonSandboxGatewayServiceImpl extends DubboPythonSandboxServiceTriple.PythonSandboxServiceImplBase {

    private final RestTemplate restTemplate;

    @Value("${sandbox.service.url}")
    private String sandboxUrl;

    public PythonSandboxGatewayServiceImpl(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public ExecuteResponse createTask(ExecuteRequest request) {
        long startMs = System.currentTimeMillis();
        // 260605-2 §3: gateway-side observability — log entry + RestTemplate duration.
        // We log code length (not content) and counts to keep INFO lines bounded and avoid PII/code leakage.
        int codeLen = request.getCode() == null ? 0 : request.getCode().length();
        log.info("sandbox.createTask: datasetId={}, datasetIds={}, timeoutSeconds={}, filesCount={}, "
                        + "librariesCount={}, codeLen={}",
                request.getDatasetId(), request.getDatasetIdsList(),
                request.getTimeoutSeconds(), request.getFilesCount(),
                request.getLibrariesCount(), codeLen);
        try {
            HttpExecuteRequest httpRequest = new HttpExecuteRequest();
            httpRequest.setDataset_id(request.getDatasetId());
            httpRequest.setDataset_ids(request.getDatasetIdsList());
            httpRequest.setCode(request.getCode());
            httpRequest.setFiles(request.getFilesList());
            httpRequest.setLibraries(request.getLibrariesList());
            if (request.getTimeoutSeconds() > 0) {
                httpRequest.setTimeout_seconds(request.getTimeoutSeconds());
            }

            String endpoint = sandboxUrl + "/tasks";
            long httpStart = System.currentTimeMillis();
            ResponseEntity<HttpCreateTaskResponse> response = restTemplate.postForEntity(
                    endpoint, httpRequest, HttpCreateTaskResponse.class);
            log.info("sandbox.http: endpoint=POST {}, httpStatus={}, durationMs={}",
                    endpoint, response.getStatusCode().value(), System.currentTimeMillis() - httpStart);

            if (response.getBody() != null) {
                log.info("sandbox.createTask.result: taskId={}, status={}, totalDurationMs={}",
                        response.getBody().getTask_id(), response.getBody().getStatus(),
                        System.currentTimeMillis() - startMs);
                return ExecuteResponse.newBuilder()
                        .setTaskId(response.getBody().getTask_id())
                        .setStatus(response.getBody().getStatus())
                        .build();
            } else {
                log.warn("sandbox.createTask.emptyBody: totalDurationMs={}", System.currentTimeMillis() - startMs);
                return ExecuteResponse.newBuilder().setError("Empty response from sandbox").build();
            }
        } catch (Exception e) {
            log.error("sandbox.createTask.failed: totalDurationMs={}, error={}",
                    System.currentTimeMillis() - startMs, e.getMessage(), e);
            return ExecuteResponse.newBuilder().setError(e.getMessage()).build();
        }
    }

    @Override
    public TaskStatusResponse getTaskStatus(GetTaskStatusRequest request) {
        long startMs = System.currentTimeMillis();
        log.info("sandbox.getTaskStatus: taskId={}", request.getTaskId());
        try {
            String endpoint = sandboxUrl + "/tasks/" + request.getTaskId();
            long httpStart = System.currentTimeMillis();
            ResponseEntity<HttpTask> response = restTemplate.getForEntity(endpoint, HttpTask.class);
            log.info("sandbox.http: endpoint=GET {}, httpStatus={}, durationMs={}",
                    endpoint, response.getStatusCode().value(), System.currentTimeMillis() - httpStart);

            if (response.getBody() != null) {
                HttpTask task = response.getBody();
                TaskStatusResponse.Builder builder = TaskStatusResponse.newBuilder()
                        .setTaskId(task.getTask_id())
                        .setStatus(task.getStatus());
                if (task.getStarted_at() != null) builder.setStartedAt(task.getStarted_at());
                if (task.getFinished_at() != null) builder.setFinishedAt(task.getFinished_at());
                if (task.getError() != null) builder.setError(task.getError());
                log.info("sandbox.getTaskStatus.result: taskId={}, status={}, totalDurationMs={}",
                        task.getTask_id(), task.getStatus(), System.currentTimeMillis() - startMs);
                return builder.build();
            } else {
                log.warn("sandbox.getTaskStatus.emptyBody: taskId={}, totalDurationMs={}",
                        request.getTaskId(), System.currentTimeMillis() - startMs);
                return TaskStatusResponse.newBuilder().setStatus("UNKNOWN").setError("Task not found").build();
            }
        } catch (HttpClientErrorException.NotFound e) {
            log.info("sandbox.getTaskStatus.notFound: taskId={}, totalDurationMs={}",
                    request.getTaskId(), System.currentTimeMillis() - startMs);
            return TaskStatusResponse.newBuilder().setStatus("UNKNOWN").setError("Task not found").build();
        } catch (Exception e) {
            log.error("sandbox.getTaskStatus.failed: taskId={}, totalDurationMs={}, error={}",
                    request.getTaskId(), System.currentTimeMillis() - startMs, e.getMessage(), e);
            return TaskStatusResponse.newBuilder().setStatus("UNKNOWN").setError(e.getMessage()).build();
        }
    }

    @Override
    public TaskResultResponse getTaskResult(GetTaskResultRequest request) {
        long startMs = System.currentTimeMillis();
        log.info("sandbox.getTaskResult: taskId={}", request.getTaskId());
        try {
            // Check status first to ensure we don't hit 409
            TaskStatusResponse status = getTaskStatus(GetTaskStatusRequest.newBuilder().setTaskId(request.getTaskId()).build());
            if ("SUCCEEDED".equals(status.getStatus())) {
                String endpoint = sandboxUrl + "/tasks/" + request.getTaskId() + "/result";
                long httpStart = System.currentTimeMillis();
                ResponseEntity<HttpExecuteResult> response = restTemplate.getForEntity(endpoint, HttpExecuteResult.class);
                log.info("sandbox.http: endpoint=GET {}, httpStatus={}, durationMs={}",
                        endpoint, response.getStatusCode().value(), System.currentTimeMillis() - httpStart);
                if (response.getBody() != null) {
                    HttpExecuteResult res = response.getBody();
                    int stdoutLen = res.getStdout() == null ? 0 : res.getStdout().length();
                    int stderrLen = res.getStderr() == null ? 0 : res.getStderr().length();
                    log.info("sandbox.getTaskResult.succeeded: taskId={}, exitCode={}, stdoutLen={}, stderrLen={}, "
                                    + "totalDurationMs={}",
                            request.getTaskId(), res.getExit_code(), stdoutLen, stderrLen,
                            System.currentTimeMillis() - startMs);
                    return TaskResultResponse.newBuilder()
                            .setTaskId(request.getTaskId())
                            .setStatus("SUCCEEDED")
                            .setExitCode(res.getExit_code())
                            .setStdout(res.getStdout() != null ? res.getStdout() : "")
                            .setStderr(res.getStderr() != null ? res.getStderr() : "")
                            .setDatasetDir(res.getDataset_dir() != null ? res.getDataset_dir() : "")
                            .build();
                }
            } else if ("FAILED".equals(status.getStatus())) {
                log.info("sandbox.getTaskResult.failedStatus: taskId={}, totalDurationMs={}",
                        request.getTaskId(), System.currentTimeMillis() - startMs);
                return TaskResultResponse.newBuilder()
                        .setTaskId(request.getTaskId())
                        .setStatus("FAILED")
                        .setError(status.getError())
                        .build();
            }

            log.info("sandbox.getTaskResult.notReady: taskId={}, status={}, totalDurationMs={}",
                    request.getTaskId(), status.getStatus(), System.currentTimeMillis() - startMs);
            return TaskResultResponse.newBuilder()
                    .setTaskId(request.getTaskId())
                    .setStatus(status.getStatus())
                    .setError("Result not available (Task " + status.getStatus() + ")")
                    .build();

        } catch (Exception e) {
            log.error("sandbox.getTaskResult.failed: taskId={}, totalDurationMs={}, error={}",
                    request.getTaskId(), System.currentTimeMillis() - startMs, e.getMessage(), e);
            return TaskResultResponse.newBuilder().setError(e.getMessage()).build();
        }
    }

    // Inner DTOs for JSON mapping
    @Data
    static class HttpExecuteRequest {
        private String dataset_id;
        private List<String> dataset_ids;
        private String code;
        private List<String> files;
        private List<String> libraries;
        private Double timeout_seconds;
    }

    @Data
    static class HttpCreateTaskResponse {
        private String task_id;
        private String status;
    }

    @Data
    static class HttpTask {
        private String task_id;
        private String status;
        private String error;
        private String started_at;
        private String finished_at;
    }
    
    @Data
    static class HttpExecuteResult {
        private int exit_code;
        private String stdout;
        private String stderr;
        private String dataset_dir;
    }
}
