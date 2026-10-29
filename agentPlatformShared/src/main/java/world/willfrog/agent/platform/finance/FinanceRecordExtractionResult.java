package world.willfrog.agent.platform.finance;

import world.willfrog.agent.platform.prompt.TerminalResultPromptTexts;
import java.util.List;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;

/** Processor output split into backend persistence, de-marked stdout/stderr, and model-safe notices. */
public record FinanceRecordExtractionResult(
        FinanceRecordBatch batch,
        List<FinanceMetricRecord> records,
        String ordinaryStdout,
        String ordinaryStderr,
        List<ModelNotice> modelNotices,
        boolean persisted) {

    public FinanceRecordExtractionResult {
        records = records == null ? List.of() : List.copyOf(records);
        ordinaryStdout = ordinaryStdout == null ? "" : ordinaryStdout;
        ordinaryStderr = ordinaryStderr == null ? "" : ordinaryStderr;
        modelNotices = modelNotices == null ? List.of() : List.copyOf(modelNotices);
    }

    public record ModelNotice(String code, String message, String action,
                              PromptText messagePrompt, PromptText actionPrompt) {
        /** 旧构造只提供文字，不能证明这些说明和建议的来源。 */
        public ModelNotice(String code, String message, String action) {
            this(code, message, action, null, null);
        }

        public static ModelNotice withPrompts(String code, PromptText message, PromptText action) {
            if (message == null || action == null) {
                throw TerminalResultPromptTexts.missing("金融结果通知缺少明确正文来源");
            }
            return new ModelNotice(code, message.text(), action.text(), message, action);
        }
    }
}
