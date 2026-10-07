package world.willfrog.agent.platform.dataanalysis;

/** 旧放行决定有记录，但原始完整请求无法确认；停止本次 Run，不能重新派发脚本。 */
public class PythonRiskReplayEvidenceMissingException extends RuntimeException {
    public PythonRiskReplayEvidenceMissingException(String operationId) {
        super("Python 审查决定已有记录，但原调用完整请求缺失或不一致：" + operationId);
    }
}
