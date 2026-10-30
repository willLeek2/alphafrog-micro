package world.willfrog.runs.driver;

import com.google.protobuf.TextFormat;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.rpc.RpcContext;
import world.willfrog.alphafrogmicro.agent.idl.AgentArtifactMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunEventMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunResultMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunStatusMessage;
import world.willfrog.alphafrogmicro.agent.idl.CancelAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.CreateAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.DownloadAgentArtifactRequest;
import world.willfrog.alphafrogmicro.agent.idl.DownloadAgentArtifactResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentCreditsRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentCreditsResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunCreditsRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunCreditsResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunResultRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunStatusRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 014-data-04 主仓库侧 run 驱动器（c6/c7/c8 用）。
 *
 * <p>一条真实 run 打穿：Dubbo tri 直连泳道 agentLangchainService（group=langchain，
 * 接口 {@code AgentDubboService}），以直通用户身份（请求 user_id 字段，服务端
 * createRun 直接采信，无登录态）创建 run，提示词让模型按顺序调
 * listMyData → executePython（dataset_ids 混传纯数字 run 级编号 + ds_ 平台对象 id）
 * → promoteDatasetToPlatform（8 必填参数）；轮询到终态后拉取事件流水与结果，
 * 全部应答原文落盘 evidence。
 *
 * <p>只负责打穿与取证，不做数据库侧断言（那是 DataFrog 侧断言程序的事）。
 * 本地构建只需 maven + JDK17，不连泳道；真跑由泳道侧按验收顺序执行。
 *
 * <p>014-data-05 phase 2 复用形态（--run-id + --mod-gate）：05 的 run 由修改任务
 * 受理端点（HTTP）自行 createRun，驱动器跳过建 run，从 runId 进轮询+取证；
 * 核对门换成 05 修改任务门（executePython 发生 + 结构化负载里 csv 附件标记恰一个）。
 * 受理/查询/守卫编排与登记段权威计数都在 05 e2e 程序（HTTP 侧），两段以 runId 衔接；
 * run 终态词表仍是 COMPLETED（task 侧 SUCCEEDED 由 e2e 查询断言，不在这判）。
 *
 * <p>014-new-03 产物通道模式（--generate-artifacts + --artifacts-probe）：创建 run 时置
 * generate_artifacts=true，executePython 收割的交付文件注册为正式产物（type=run_output）；
 * run 终态取证后跑探针——listArtifacts 清单全文落盘 artifacts-list.txt，run_output 逐个
 * downloadArtifact 下载到 artifacts/ 并记 SHA-256 账（artifacts-sha256.txt），非 run_output
 * 只记清单不下载；探针整体尽力而为，失败不改退出码。C3 取消路径另配
 * --cancel-after-seconds：轮询超过该秒数仍未终态时发一次 cancelRun，随后照常轮询到终态
 * （终态 CANCELED 与第一个任务产物保留的断言在泳道侧判）。
 *
 * <p>流程（详见 --help）：
 * createRun（或 --run-id 挂已有 run）→ 轮询 getStatus 到终态（超时可配）
 * → getRun + getResult + listEvents（翻页全量）+ getRunCredits（尽力而为）
 * → evidence 落盘 → 退出码 0 = 终态 COMPLETED 且三个工具调用齐（可 --no-tool-gate 关门）。
 */
public final class RunDriverMain {

    // ---------------------------------------------------------------------
    // 默认连接与模型参数（全部可用命令行覆盖）
    // ---------------------------------------------------------------------

    /** 泳道 agentLangchainService 的 tri 直连地址（c6-c8 泳道映射端口）。 */
    private static final String DEFAULT_AGENT_URL = "tri://172.27.49.47:53400";
    /** provider 注册 group：agentLangchainService 固定 group=langchain。 */
    private static final String DEFAULT_GROUP = "langchain";
    /** 直通用户 id（字符串形式直接放进请求 user_id；服务端无鉴权，只查 credit）。 */
    private static final String DEFAULT_USER = "5";
    /** 02 先例口径：便宜模型，经 stage_config_json 按阶段覆盖服务端默认。 */
    private static final String DEFAULT_MODEL = "deepseek/deepseek-v4.1-flash";
    private static final String DEFAULT_ENDPOINT = "openrouter";

    /** 终态集合（AgentRunStatus 枚举；CANCELING / WAITING_TOOL_JOB 是过程态，不能停轮询）。 */
    private static final List<String> TERMINAL_STATUSES =
            List.of("COMPLETED", "PARTIAL", "FAILED", "CANCELED", "EXPIRED");

    // ---------------------------------------------------------------------
    // 提示词模板（frog 交付评审时改这里；占位符 {{XXX}} 由驱动参数填入）
    // ---------------------------------------------------------------------

    /** 第二步挂载专用：不寻址数据（沙箱内按编号寻址是平台约定），打印标记并原文输出 paths_dataset.csv（original_id 溯源证据）。 */
    private static final String CODE_MOUNT_TEMPLATE = """
            print("MOUNT_ONLY_STEP_DONE")
            try:
                print(open("/sandbox/paths_dataset.csv").read())
            except Exception as e:
                print("paths_dataset.csv read failed:", e)
            """;

    /** 第三步按编号读：__NUMBER__ 由模型按第二步结果里的数字编号替换（驱动渲染期编号未知）。 */
    private static final String CODE_READ_TEMPLATE = """
            from af_dataset_loader import load_datasets
            datasets = load_datasets("__NUMBER__")
            total = 0
            for key in sorted(datasets):
                df = datasets[key]
                print(f"{key}: rows={len(df)}")
                total = total + len(df)
            print(f"TOTAL_ROWS={total}")
            """;

    /**
     * 发给模型的中文指令：六步定序（listMyData→挂载专用 executePython→混传 executePython→
     * getStockDaily 取数→listMyData 分配编号→promoteDatasetToPlatform），失败如实转述后停止。
     * 第四~六步是 20261028 守卫裁定后改的：挂载副本不许再提升（PLATFORM_OBJECT_SELF_PROMOTE，
     * 守卫是 bob 审过的定案行为），提升源改取数工具产出的数据集。
     * 占位符：{{DS_ID}}、{{CODE_MOUNT}}、{{CODE_READ}}、{{NAME}}、{{DESCRIPTION}}、
     * {{SEMANTIC_REFS}}、{{PRIMARY_KEY}}、{{COVERAGE_SCOPE}}、{{COVERAGE_START}}、{{COVERAGE_END}}。
     * CODE_READ 里的 __NUMBER__ 不由驱动渲染（创建时编号未知），留给模型按第二步结果替换。
     */
    private static final String PROMPT_TEMPLATE = """
            你是一个严格按步骤执行的数据核对助手。请严格按下面六步依次执行，只调用指定的四个工具（listMyData、executePython、getStockDaily、promoteDatasetToPlatform），不要调用任何其他工具，不要自行发挥，不要跳步或加步。每个工具至多调用一次、全程共六次工具调用；六步全部完成后立即写总结并停止，不要在任何后续节点重复执行第四~六步。任何一步的工具返回失败或错误时，如实转述工具返回的错误内容，停止后续步骤，直接给出失败摘要。

            第一步：调用工具 listMyData，参数 query_type 传 "dataset"，其余参数全部留空。目的是查看当前 run 的数据集列表与「平台数据对象」区，并在总结里如实转述平台区当前有哪些对象。

            第二步：调用工具 executePython 做一次挂载专用调用：
            - code 参数使用下面这段代码，原样传入，不要修改：
            {{CODE_MOUNT}}
            - dataset_ids 参数只传 "{{DS_ID}}"（仅平台对象；本步不读数据——沙箱内数据一律按 run 级编号寻址，这是平台约定）；
            - manifest_ids 留空；libraries 留空；timeout_seconds 传 120。
            目的：挂载该平台对象并登记为 run 级数据集。工具返回里会显示分配的数字编号，把它记下来；代码会打印 paths_dataset.csv 全文，把编号与平台对象 id 的对应关系一并转述。

            第三步：再调用一次工具 executePython 做混传调用：
            - code 参数使用下面这段代码，并把其中 load_datasets("__NUMBER__") 里的 __NUMBER__ 替换为第二步结果里显示的数字编号，其余原样：
            {{CODE_READ}}
            - dataset_ids 参数传「第二步结果里显示的数字编号,{{DS_ID}}"（纯数字与 ds_ 混合，一次调用、原样传入，不要拆成两次调用，不要改动数字）；
            - 其余参数同第二步。
            目的：一次调用同时挂载 run 级编号与平台对象（两套标识并存），并按编号读到数据。

            第四步：调用工具 getStockDaily，参数原样传入：ts_code 传 "000001.SZ"，start_date 传 "20260105"，end_date 传 "20260109"，includeColumns 留空。
            目的：取一小段股票日线行情，作为后续提升的数据源。把应答里的 dataset_id、rows、start_date、end_date 都记下来（start_date/end_date 是数据的实际覆盖区间，缓存复用时可能比请求区间宽，提升时以它为准）。

            第五步：再调用一次工具 listMyData，参数 query_type 传 "dataset"，其余参数留空。run 数据集列表（entries）里会出现刚才取数产生的数据集（fromTsCode 是 "000001.SZ" 的那条；列表不含平台对象挂载条目）。把它的 number 记下来。

            第六步：调用工具 promoteDatasetToPlatform，参数原样传入：
            - dataset_number 传第五步记下的 number（取数数据集的编号，不要用第二步平台挂载的编号）；
            - name 传 "{{NAME}}"
            - description 传 "{{DESCRIPTION}}"
            - semantic_refs 传 "{{SEMANTIC_REFS}}"
            - primary_key 传 "{{PRIMARY_KEY}}"
            - coverage_scope 传 "{{COVERAGE_SCOPE}}"
            - coverage_start 传第四步记下的 start_date（YYYYMMDD 转成 YYYY-MM-DD，用实际值）
            - coverage_end 传第四步记下的 end_date（同样转格式，用实际值）
            - frequency 传 "daily"

            六步全部结束后，用不超过八行文字总结：每一步调用的工具名、是否成功；第一步转述平台区对象概况；第二步给出数字编号与 paths_dataset.csv 里平台对象的对应；第三步给出代码输出（行数）；第四步给出 dataset_id、行数与实际覆盖区间；第五步给出编号；第六步给出新对象的 ds_ id（如成功）或如实转述错误内容。
            """;

    /** c9 降级探测模板（--expect-degraded 用）：DataFrog 半边停机窗口内跑，五步，
     * 每步的失败/降级本身就是断言对象（预期失败=继续下一步，全部转述原文）。
     * 甲方案（拿六步模板进窗口）走不通：第二步 ds_ 挂载失败会触发「失败即停」规则，
     * promote 永远不会被调到，第三条断言（TOOL_ERROR）测不到。 */
    private static final String C9_PROMPT_TEMPLATE = """
            你是一个降级窗口探测助手。当前平台数据对象服务（DataFrog）已停机。请严格按下面五步依次执行，只调用指定的四个工具（listMyData、executePython、getStockDaily、promoteDatasetToPlatform），不要调用任何其他工具。本任务里前两步与第五步预期返回失败或降级结果——失败不是异常，是本次要观测的事实：如实逐字转述每步的返回原文，转述完继续下一步，不要因为失败而停止或重试。

            第一步：调用工具 listMyData，参数 query_type 传 "dataset"，其余参数留空。预期：调用成功返回，但「平台数据对象」区为空、并附带不可用说明（note）。把 note 原文逐字转述。

            第二步：调用工具 executePython，code 参数传下面这段（原样）：
            print("C9_PROBE_DONE")
            dataset_ids 只传 "{{DS_ID}}"。预期：结构化失败，错误码形如 PLATFORM_OBJECT_UNAVAILABLE，且不会创建沙箱任务。把错误码与 message 原文逐字转述。

            第三步：调用工具 getStockDaily，参数 ts_code="000001.SZ"、start_date="20260105"、end_date="20260109"、includeColumns 留空。预期：正常成功（取数不经 DataFrog）。记下 dataset_id、rows、start_date、end_date。

            第四步：再调用一次工具 listMyData，参数 query_type 传 "dataset"。取数数据集会出现（fromTsCode="000001.SZ" 那条），记下它的 number（平台区此时仍为空+note，照常转述一次 note）。

            第五步：调用工具 promoteDatasetToPlatform，参数：dataset_number 传第四步的 number；name 传 "{{NAME}}"；description 传 "{{DESCRIPTION}}"；semantic_refs 传 "{{SEMANTIC_REFS}}"；primary_key 传 "{{PRIMARY_KEY}}"；coverage_scope 传 "{{COVERAGE_SCOPE}}"；coverage_start 传第三步记下的 start_date（YYYYMMDD 转 YYYY-MM-DD）；coverage_end 传第三步记下的 end_date（同样转格式）；frequency 传 "daily"。预期：TOOL_ERROR（promoteDatasetToPlatform invocation error 之类的最外层包装错误），不产生新对象。把错误码与 message 原文逐字转述。

            五步结束后用不超过六行总结：每步工具名、预期是否兑现、关键错误码/note 原文。
            """;

    // ---------------------------------------------------------------------
    // 状态
    // ---------------------------------------------------------------------

    private final List<Check> checks = new ArrayList<>();
    private final StringBuilder pollLog = new StringBuilder();
    private String terminalStatus;
    private boolean timedOut;

    private record Check(String name, boolean pass, String detail) {}

    public static void main(String[] args) throws Exception {
        // dubbo 日志降噪（不改调用方显式配置）
        if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        }
        Map<String, String> opts;
        try {
            opts = parseArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误: " + e.getMessage());
            printUsage();
            System.exit(2);
            return;
        }
        if ("1".equals(opts.get("--help")) || opts.isEmpty()) {
            printUsage();
            System.exit(0);
            return;
        }
        new RunDriverMain().run(opts);
        System.exit(0); // 不会到这（run 内部决定退出码），保险
    }

    /** 泳道标签：非空时给每次出站调用带上官方 dubbo.tag 附件。
     * 直连（不经 tag 路由）时提供方按附件恢复泳道上下文；缺了它 createRun 落库 lane_tag 为空，
     * 租约续期与补扫按进程泳道筛选，运行标签与进程不一致时无法续期或补扫。 */
    private String laneTag = "";

    /** 每次远程调用前把泳道标签写入 RpcContext 客户端附件。
     * 本独立驱动未引入共享 common 过滤器；引入后应通过 LaneContext 传递标签。 */
    private void laneTagAttachment() {
        if (laneTag != null && !laneTag.isBlank()) {
            RpcContext.getClientAttachment().setAttachment("dubbo.tag", laneTag);
        }
    }

    private void run(Map<String, String> opts) throws Exception {
        String agentUrl = opts.getOrDefault("--agent-url", DEFAULT_AGENT_URL);
        String group = opts.getOrDefault("--group", DEFAULT_GROUP);
        this.laneTag = opts.getOrDefault("--lane-tag", "");
        if (!this.laneTag.isBlank()) {
            info("泳道标签附件已启用: dubbo.tag=" + this.laneTag);
        }
        String user = opts.getOrDefault("--user", DEFAULT_USER);
        String model = opts.getOrDefault("--model", DEFAULT_MODEL);
        String endpoint = opts.getOrDefault("--endpoint", DEFAULT_ENDPOINT);
        String dsId = opts.getOrDefault("--ds-id", "");
        String datasetNumber = opts.getOrDefault("--dataset-number", "1");
        String promoteNumber = opts.getOrDefault("--promote-number", datasetNumber);
        String name = opts.getOrDefault("--name", "acc04_promoted");
        String description = opts.getOrDefault("--description", "014-data-04 c8 提升验收对象");
        String semanticRefs = opts.getOrDefault("--semantic-ref", "acc04_sem@1");
        String primaryKey = opts.getOrDefault("--primary-key", "trade_date");
        String coverageScope = opts.getOrDefault("--coverage-scope", "014-data-04 验收数据");
        String coverageStart = opts.getOrDefault("--coverage-start", "2026-01-01");
        String coverageEnd = opts.getOrDefault("--coverage-end", "2026-01-31");
        String csvPath = opts.getOrDefault("--csv", "");
        String outRoot = opts.getOrDefault("--out", "out-014data04");
        String attachRunId = opts.getOrDefault("--run-id", "");
        String messageFile = opts.getOrDefault("--message-file", "");
        long timeoutSeconds = Long.parseLong(opts.getOrDefault("--timeout-seconds", "900"));
        long pollIntervalSeconds = Long.parseLong(opts.getOrDefault("--poll-interval-seconds", "5"));
        // C3 取消路径：轮询超过该秒数仍未终态时发一次 cancelRun（0=关，默认关）
        long cancelAfterSeconds = Long.parseLong(opts.getOrDefault("--cancel-after-seconds", "0"));
        boolean captureLlm = "1".equals(opts.get("--capture-llm"));
        boolean expectDegraded = "1".equals(opts.get("--expect-degraded"));
        boolean modGate = "1".equals(opts.get("--mod-gate"));
        String modCsvGate = opts.getOrDefault("--mod-csv-gate", "hard");
        boolean toolGate = !"1".equals(opts.get("--no-tool-gate"));

        boolean attachMode = !attachRunId.isBlank();
        if (!attachMode && dsId.isBlank()) {
            System.err.println("创建模式必须给 --ds-id（ds_ 平台对象 id）。看 --help。");
            System.exit(2);
        }
        if (dsId.startsWith("ds_") == false && !dsId.isBlank()) {
            System.err.println("提醒：--ds-id=\"" + dsId + "\" 不是 ds_ 前缀，executePython 会按 run 级编号解析而不是平台对象。继续执行。");
        }

        // 提示词与模型配置（attach 模式不需要）
        String codeMount = CODE_MOUNT_TEMPLATE;
        String codeRead = CODE_READ_TEMPLATE; // __NUMBER__ 留给模型按第二步结果替换
        String message;
        if (!messageFile.isBlank()) {
            message = Files.readString(Path.of(messageFile), StandardCharsets.UTF_8);
            System.out.println("提示词来自文件（不替换占位符）: " + messageFile);
        } else if (attachMode && modGate) {
            // 05 复用形态：run 由受理端点创建，驱动器不发提示词，占位内容只进 params.txt 留档
            message = "（attach+mod 模式：不发送提示词——run 由 014-data-05 修改任务受理端点创建，驱动器只跟踪与取证）";
        } else {
            message = render(expectDegraded ? C9_PROMPT_TEMPLATE : PROMPT_TEMPLATE, Map.ofEntries(
                    Map.entry("{{DS_ID}}", dsId),
                    Map.entry("{{CODE_MOUNT}}", indentCodeBlock(codeMount)),
                    Map.entry("{{CODE_READ}}", indentCodeBlock(codeRead)),
                    Map.entry("{{NAME}}", name),
                    Map.entry("{{DESCRIPTION}}", description),
                    Map.entry("{{SEMANTIC_REFS}}", semanticRefs),
                    Map.entry("{{PRIMARY_KEY}}", primaryKey),
                    Map.entry("{{COVERAGE_SCOPE}}", coverageScope)));
        }
        // 三个阶段全量覆盖（planning 阶段 isValid 需要 endpoint+model 都非空才不回落执行模型）
        String stageConfigJson = "{\"execution\":{\"endpointName\":\"" + endpoint
                + "\",\"modelName\":\"" + model + "\"},"
                + "\"planning\":{\"endpointName\":\"" + endpoint
                + "\",\"modelName\":\"" + model + "\"},"
                + "\"final_answer\":{\"endpointName\":\"" + endpoint
                + "\",\"modelName\":\"" + model + "\"}}";

        // evidence 目录先建好，创建请求原文第一时间落盘（失败也有据可查）
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        Path outDir = Path.of(outRoot, stamp + (attachMode ? "-attach" : ""));
        Files.createDirectories(outDir);
        writeParams(outDir, opts, message, codeMount + "\n===== code-read =====\n" + codeRead, stageConfigJson);

        // 本地 CSV 期望行数（软核对用）
        Long csvDataRows = null;
        if (!csvPath.isBlank()) {
            Path p = Path.of(csvPath);
            if (Files.isRegularFile(p)) {
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                csvDataRows = (long) lines.stream().skip(1).filter(l -> !l.isBlank()).count();
                info("本地 CSV " + csvPath + "：数据行（不含表头、不含空行）=" + csvDataRows);
            } else {
                info("提醒：--csv 路径不是普通文件，跳过行数预读: " + csvPath);
            }
        }

        // -----------------------------------------------------------------
        // Dubbo 直连
        // -----------------------------------------------------------------
        AgentDubboService svc;
        try {
            ReferenceConfig<AgentDubboService> ref = new ReferenceConfig<>();
            ref.setInterface(AgentDubboService.class);
            ref.setUrl(agentUrl);
            ref.setGroup(group);
            ref.setTimeout(60_000);
            DubboBootstrap bootstrap = DubboBootstrap.getInstance()
                    .application(new ApplicationConfig("run-driver-014data04"))
                    .reference(ref)
                    .start();
            svc = bootstrap.getCache().get(AgentDubboService.class);
        } catch (Exception e) {
            System.err.println("Dubbo 直连失败: " + agentUrl + " group=" + group + " : " + e);
            e.printStackTrace();
            System.exit(2);
            return;
        }

        // credit 预检（尽力而为，失败不拦）
        try {
            laneTagAttachment();
            GetAgentCreditsResponse credits = svc.getCredits(GetAgentCreditsRequest.newBuilder()
                    .setUserId(user).build());
            info("credit 预检: total=" + credits.getTotalCredits()
                    + " remaining=" + credits.getRemainingCredits());
        } catch (Exception e) {
            info("credit 预检失败（不拦流程）: " + e.getMessage());
        }

        // -----------------------------------------------------------------
        // 创建 run（或挂已有 run）
        // -----------------------------------------------------------------
        String runId = attachRunId;
        if (!attachMode) {
            CreateAgentRunRequest.Builder req = CreateAgentRunRequest.newBuilder()
                    .setUserId(user)
                    .setMessage(message)
                    .setStageConfigJson(stageConfigJson)
                    .setCaptureLlmRequests(captureLlm)
                    .setGenerateArtifacts(opts.containsKey("--generate-artifacts"));
            AgentRunMessage created;
            try {
                laneTagAttachment();
                created = svc.createRun(req.build());
            } catch (Exception e) {
                System.err.println("createRun 失败: " + e);
                e.printStackTrace();
                System.exit(2);
                return;
            }
            writeText(outDir.resolve("create-response.txt"), protoText(created));
            runId = created.getId();
            check("create.run_id_nonempty", !runId.isBlank(), "runId=" + runId);
            System.out.println("RUN_ID=" + runId);
        } else {
            System.out.println("RUN_ID=" + runId + "（attach 模式，不创建新 run）");
        }

        // -----------------------------------------------------------------
        // 轮询到终态
        // -----------------------------------------------------------------
        long pollStartMs = System.currentTimeMillis();
        long deadline = pollStartMs + timeoutSeconds * 1000;
        AgentRunStatusMessage lastStatus = null;
        int consecutiveErrors = 0;
        boolean cancelSent = false; // cancelRun 只发一次（失败也算发过，防重发）
        while (System.currentTimeMillis() < deadline) {
            try {
                laneTagAttachment();
                lastStatus = svc.getStatus(GetAgentRunStatusRequest.newBuilder()
                        .setUserId(user).setId(runId).build());
                consecutiveErrors = 0;
                String line = LocalDateTime.now() + " status=" + lastStatus.getStatus()
                        + " phase=" + empty(lastStatus.getPhase())
                        + " current_tool=" + empty(lastStatus.getCurrentTool())
                        + " events=" + lastStatus.getEventCount()
                        + " elapsed_ms=" + lastStatus.getElapsedMs();
                System.out.println(line);
                pollLog.append(line).append(System.lineSeparator());
                if (TERMINAL_STATUSES.contains(lastStatus.getStatus())) {
                    terminalStatus = lastStatus.getStatus();
                    break;
                }
            } catch (Exception e) {
                consecutiveErrors++;
                String line = LocalDateTime.now() + " poll_error(" + consecutiveErrors + ")=" + e;
                System.err.println(line);
                pollLog.append(line).append(System.lineSeparator());
                if (consecutiveErrors >= 5) {
                    System.err.println("连续 5 次轮询失败，中止。");
                    writeEvidence(outDir, runId, user, svc, csvDataRows, model, lastStatus, expectDegraded, modGate, modCsvGate);
                    System.exit(2);
                    return;
                }
            }
            // C3 取消路径（--cancel-after-seconds）：本轮未到终态且轮询已超阈值 → 发一次 cancelRun；
            // 发过（含失败）不再重发，照常轮询到终态（CANCELED 与产物保留的断言在泳道侧判）。
            if (cancelAfterSeconds > 0 && !cancelSent
                    && System.currentTimeMillis() - pollStartMs > cancelAfterSeconds * 1000) {
                cancelSent = true;
                try {
                    laneTagAttachment();
                    svc.cancelRun(CancelAgentRunRequest.newBuilder()
                            .setUserId(user).setId(runId).build());
                    System.out.println("CANCEL_SENT status="
                            + (lastStatus == null ? "UNKNOWN" : lastStatus.getStatus()));
                } catch (Exception e) {
                    System.out.println("CANCEL_FAILED: " + e);
                }
            }
            sleep(pollIntervalSeconds);
        }
        if (terminalStatus == null) {
            timedOut = true;
            terminalStatus = lastStatus == null ? "UNKNOWN" : lastStatus.getStatus();
        }

        // -----------------------------------------------------------------
        // 终态后取证
        // -----------------------------------------------------------------
        writeEvidence(outDir, runId, user, svc, csvDataRows, model, lastStatus, expectDegraded, modGate, modCsvGate);

        // 014-new-03 产物探针（--artifacts-probe）：终态取证后跑，尽力而为不改退出码；
        // 超时未到终态时 run 未定稿，跳过探针（产物清单大概率不全，以真终态那次为准）。
        if (opts.containsKey("--artifacts-probe") && !timedOut) {
            probeArtifacts(outDir, runId, user, svc);
        }

        boolean completed = "COMPLETED".equals(terminalStatus);
        check("status.terminal_completed", completed,
                "terminal=" + terminalStatus + (timedOut ? "（超时未到终态）" : ""));

        // 退出码
        int exit;
        if (timedOut) {
            exit = 4;
        } else if (!completed) {
            exit = 3;
        } else {
            boolean gateOk = checks.stream().allMatch(c -> c.pass());
            exit = (toolGate && !gateOk) ? 1 : 0;
        }
        System.out.println("EXIT=" + exit + " terminal=" + terminalStatus);
        System.exit(exit);
    }

    /** 终态取证：run 详情、结果、事件全量、credit 明细（尽力而为）+ 工具调用核对。 */
    private void writeEvidence(Path outDir, String runId, String user, AgentDubboService svc,
                               Long csvDataRows, String model, AgentRunStatusMessage lastStatus,
                               boolean expectDegraded, boolean modGate, String modCsvGate) {
        AgentRunMessage runMsg = null;
        try {
            laneTagAttachment();
            runMsg = svc.getRun(GetAgentRunRequest.newBuilder().setUserId(user).setId(runId).build());
            writeText(outDir.resolve("run-message.txt"), protoText(runMsg));
        } catch (Exception e) {
            info("getRun 失败（证据缺 run-message.txt）: " + e.getMessage());
        }
        // 结构化结果面（csv 标记统计用）：payload_json / structured_answer_json 是服务端结构，answerMarkdown 是模型行文不算
        String resultPayloadJson = null;
        String resultStructuredJson = null;
        try {
            laneTagAttachment();
            AgentRunResultMessage result = svc.getResult(GetAgentRunResultRequest.newBuilder()
                    .setUserId(user).setId(runId).build());
            resultPayloadJson = result.getPayloadJson();
            resultStructuredJson = result.getStructuredAnswerJson();
            StringBuilder sb = new StringBuilder(protoText(result));
            sb.append(System.lineSeparator())
              .append("===== answer_markdown 原文 =====").append(System.lineSeparator())
              .append(result.getAnswerMarkdown()).append(System.lineSeparator());
            writeText(outDir.resolve("result.txt"), sb.toString());
        } catch (Exception e) {
            info("getResult 失败（证据缺 result.txt）: " + e.getMessage());
        }
        try {
            laneTagAttachment();
            GetAgentRunCreditsResponse credits = svc.getRunCredits(GetAgentRunCreditsRequest.newBuilder()
                    .setUserId(user).setId(runId).build());
            writeText(outDir.resolve("run-credits.txt"), protoText(credits));
            boolean modelSeen = credits.getRecordsCount() == 0 || credits.getRecordsList().stream()
                    .allMatch(c -> c.getModel().isBlank() || c.getModel().contains(model));
            info("run credits: calls=" + credits.getRecordsCount()
                    + " total_credits=" + credits.getTotalCredits()
                    + (modelSeen ? "" : "（注意：有调用模型与指定模型不一致）"));
        } catch (Exception e) {
            info("getRunCredits 失败（不影响判读）: " + e.getMessage());
        }

        // 事件全量翻页
        List<AgentRunEventMessage> events = new ArrayList<>();
        try {
            int afterSeq = 0;
            while (true) {
                laneTagAttachment();
                ListAgentRunEventsResponse page = svc.listEvents(ListAgentRunEventsRequest.newBuilder()
                        .setUserId(user).setId(runId).setAfterSeq(afterSeq).setLimit(200).build());
                events.addAll(page.getItemsList());
                if (!page.getHasMore() || page.getItemsCount() == 0) {
                    break;
                }
                afterSeq = page.getNextAfterSeq();
            }
            StringBuilder jsonl = new StringBuilder();
            for (AgentRunEventMessage ev : events) {
                jsonl.append("{\"seq\":").append(ev.getSeq())
                        .append(",\"event_type\":\"").append(escape(ev.getEventType()))
                        .append("\",\"created_at\":\"").append(escape(ev.getCreatedAt()))
                        .append("\",\"payload\":").append(nvlJson(ev.getPayloadJson()))
                        .append("}").append(System.lineSeparator());
            }
            writeText(outDir.resolve("events.jsonl"), jsonl.toString());
        } catch (Exception e) {
            info("listEvents 失败（工具核对将不可用）: " + e.getMessage());
        }

        // 工具调用核对（硬检查；payload 是服务端 Jackson 紧凑 JSON，用包含+正则取值）
        List<String> pyStarted = allToolStartedPayloads(events, "executePython");
        check("tool.executePython.started", !pyStarted.isEmpty(), pyStarted.isEmpty() ? "未找到" : shorten(pyStarted.get(0)));
        if (modGate) {
            // 05 修改任务门：附件「恰一个 CSV」在 run 侧按结构化负载里的 csv 标记去重统计；
            // 登记段的权威计数（contentType 含 csv / 文件名 .csv 过滤后恰一）在 05 e2e 侧，这里是对应的 run 侧旁证。
            java.util.LinkedHashSet<String> csvMarkers = new java.util.LinkedHashSet<>();
            collectCsvMarkers(resultPayloadJson, csvMarkers);
            collectCsvMarkers(resultStructuredJson, csvMarkers);
            for (AgentRunEventMessage ev : events) {
                collectCsvMarkers(ev.getPayloadJson(), csvMarkers);
            }
            int csvCount = csvMarkers.size();
            String detail = "去重 csv 标记=" + csvCount + (csvMarkers.isEmpty() ? "" : " " + csvMarkers)
                    + "（登记段权威计数在 05 e2e 侧）";
            if ("off".equals(modCsvGate)) {
                info("mod.csv_attachment_single（门已关，仅记录）: " + detail);
            } else {
                check("mod.csv_attachment_single", csvCount == 1, detail);
            }
            info("mod 门：04 专属核对（listMyData/getStockDaily/promote 形态）跳过");
        } else if (expectDegraded) {
            // c9 降级核对门：预期失败即通过（executePython 拒绝码族 + promote 最外层 TOOL_ERROR）。
            String pyFinished = toolFinishedPayload(events, "executePython");
            check("c9.executePython.platform_object_unavailable",
                    pyFinished != null && pyFinished.contains("PLATFORM_OBJECT_UNAVAILABLE"),
                    pyFinished == null ? "无 FINISHED 事件" : shorten(pyFinished));
        } else if (!modGate) {
            String startedList = toolStartedPayload(events, "listMyData");
            check("tool.listMyData.started", startedList != null, startedList == null ? "未找到" : shorten(startedList));
            String mixed = pyStarted.stream().map(RunDriverMain::extractDatasetIds)
                    .filter(ids -> ids != null && hasDigitToken(ids) && hasDsToken(ids)).findFirst().orElse(null);
            check("tool.executePython.dataset_ids_mixed", mixed != null,
                    mixed == null ? "没有数字+ds_ 混合的调用" : "dataset_ids=\"" + mixed + "\"");
        }
        if (!modGate) {
            String startedFetch = toolStartedPayload(events, "getStockDaily");
            check("tool.getStockDaily.started", startedFetch != null,
                    startedFetch == null ? "未找到" : shorten(startedFetch));
            String startedPromote = toolStartedPayload(events, "promoteDatasetToPlatform");
            check("tool.promoteDatasetToPlatform.started", startedPromote != null,
                    startedPromote == null ? "未找到" : shorten(startedPromote));
            String finishedPromote = toolFinishedPayload(events, "promoteDatasetToPlatform");
            boolean promoteOk;
            if (expectDegraded) {
                promoteOk = false;
                check("c9.promote.tool_error_wrapped",
                        finishedPromote != null && finishedPromote.contains("TOOL_ERROR")
                                && finishedPromote.toLowerCase().contains("invocation error"),
                        finishedPromote == null ? "无 FINISHED 事件" : shorten(finishedPromote));
                info("c9 降级模式：promote 预期 TOOL_ERROR（不产生新对象），ds_ 预览检查跳过");
            } else {
                promoteOk = finishedPromote != null && SUCCESS_TRUE_PATTERN.matcher(finishedPromote).find();
                check("tool.promoteDatasetToPlatform.finished_success", promoteOk,
                        finishedPromote == null ? "无 FINISHED 事件" : shorten(finishedPromote));
                if (promoteOk && finishedPromote.contains("ds_")) {
                    info("promote 应答预览含 ds_ 新对象 id（c8-34 关键返回）: " + shorten(finishedPromote));
                } else {
                    info("promote 成功但预览里没看到 ds_ id（result_preview 可能被截断，回查以 events.jsonl / result.txt 为准）");
                }
            }
        }
        if (csvDataRows != null) {
            boolean rowsSeen = events.stream().anyMatch(ev -> ev.getPayloadJson() != null
                    && ev.getPayloadJson().contains("TOTAL_ROWS=" + csvDataRows));
            info("CSV 行数核对（软）: 本地数据行=" + csvDataRows
                    + (rowsSeen ? "，已在工具结果预览中看到同值 TOTAL_ROWS" : "，预览未见同值 TOTAL_ROWS（可能被截断，人工判读 events.jsonl）"));
        }
        if (runMsg != null) {
            boolean extHasModel = runMsg.getExt() != null && runMsg.getExt().contains(model);
            info("模型指定生效（软）: run.ext 含 stage_config_json 且出现 " + model + " = " + extHasModel);
        }

        // evidence 汇总
        StringBuilder summary = new StringBuilder();
        summary.append("014-data-04 run 驱动器 evidence ").append(LocalDateTime.now())
                .append(System.lineSeparator())
                .append("runId=").append(runId)
                .append(" terminal=").append(terminalStatus)
                .append(" timedOut=").append(timedOut)
                .append(System.lineSeparator()).append(System.lineSeparator());
        if (lastStatus != null) {
            summary.append("最后一次 getStatus:").append(System.lineSeparator())
                    .append(protoText(lastStatus)).append(System.lineSeparator());
        }
        summary.append("==== 检查项 ====").append(System.lineSeparator());
        for (Check c : checks) {
            summary.append(c.pass() ? "PASS " : "FAIL ").append(c.name())
                    .append(" | ").append(c.detail()).append(System.lineSeparator());
        }
        writeText(outDir.resolve("evidence-summary.txt"), summary.toString());
        writeText(outDir.resolve("poll.log"), pollLog.toString());
        System.out.println("evidence 目录: " + outDir.toAbsolutePath());
    }

    // ---------------------------------------------------------------------
    // 014-new-03 产物通道探针（--artifacts-probe）
    // ---------------------------------------------------------------------

    /**
     * run 终态后的产物通道探针（014-new-03）。listArtifacts 拉产物清单，全文（含每项
     * type/name/content_type/meta_json）落盘 artifacts-list.txt；type=run_output 的每一项
     * downloadArtifact 下载到 {@code artifacts/<artifactId>__<filename>}（filename 里路径
     * 分隔符换下划线），内容 SHA-256 逐行记 artifacts-sha256.txt；非 run_output 只进清单
     * 不下载。整体一个 try/catch：任何失败打印 PROBE failed 并把原文追加到 artifacts-list.txt
     * 尾部，不加 check、不改退出码，主流程照常走完。
     */
    private void probeArtifacts(Path outDir, String runId, String user, AgentDubboService svc) {
        Path listPath = outDir.resolve("artifacts-list.txt");
        try {
            laneTagAttachment();
            ListAgentArtifactsResponse list = svc.listArtifacts(ListAgentArtifactsRequest.newBuilder()
                    .setUserId(user).setId(runId).build());
            // 清单先落盘再逐个下载：中途失败时清单证据已在，失败原文追加在其尾部
            writeText(listPath, "==== ListArtifacts runId=" + runId + " ====" + System.lineSeparator()
                    + protoText(list));
            info("产物探针: 清单项数=" + list.getItemsCount());
            for (AgentArtifactMessage item : list.getItemsList()) {
                String artifactId = item.getArtifactId();
                if (!"run_output".equals(item.getType())) {
                    System.out.println("PROBE artifact id=" + artifactId + " type=" + item.getType()
                            + " skipped-download");
                    continue;
                }
                laneTagAttachment();
                DownloadAgentArtifactResponse dl = svc.downloadArtifact(DownloadAgentArtifactRequest.newBuilder()
                        .setUserId(user).setArtifactId(artifactId).build());
                // 记账文件名与落盘文件名同款净化名，账和盘上文件一一对应
                String diskName = artifactId + "__" + sanitizeFilename(dl.getFilename());
                Path artifactPath = outDir.resolve("artifacts").resolve(diskName);
                Files.createDirectories(artifactPath.getParent());
                byte[] content = dl.getContent().toByteArray();
                Files.write(artifactPath, content);
                String sha256 = sha256Hex(content);
                appendText(outDir.resolve("artifacts-sha256.txt"), sha256 + "  " + diskName
                        + "  bytes=" + content.length
                        + "  contentType=" + (dl.getContentType().isBlank() ? item.getContentType() : dl.getContentType())
                        + System.lineSeparator());
                System.out.println("PROBE artifact id=" + artifactId + " type=run_output sha256=" + sha256
                        + " bytes=" + content.length);
            }
        } catch (Exception e) {
            System.out.println("PROBE failed: " + e);
            appendText(listPath, "PROBE failed: " + e + System.lineSeparator());
        }
    }

    /** 产物落盘文件名净化：路径分隔符替换成下划线（filename 可能带 a/b.csv 之类相对路径），空名兜底 unnamed。 */
    private static String sanitizeFilename(String filename) {
        String safe = (filename == null ? "" : filename).replace("/", "_").replace("\\", "_");
        return safe.isBlank() ? "unnamed" : safe;
    }

    /** 内容 SHA-256 十六进制（小写），探针下载账用。 */
    private static String sha256Hex(byte[] content) throws java.security.NoSuchAlgorithmException {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }

    /** 逐行追加写（探针记账/失败记录用；文件不存在则新建，写失败只报不打断）。 */
    private static void appendText(Path path, String text) {
        try {
            Files.writeString(path, text, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("追加证据文件失败 " + path + ": " + e);
        }
    }

    // ---------------------------------------------------------------------
    // 小工具
    // ---------------------------------------------------------------------

    private static String toolStartedPayload(List<AgentRunEventMessage> events, String tool) {
        return events.stream()
                .filter(ev -> "TOOL_CALL_STARTED".equals(ev.getEventType()))
                .map(AgentRunEventMessage::getPayloadJson)
                .filter(p -> p != null && toolNamePattern(tool).matcher(p).find())
                .findFirst().orElse(null);
    }

    private static List<String> allToolStartedPayloads(List<AgentRunEventMessage> events, String tool) {
        return events.stream()
                .filter(ev -> "TOOL_CALL_STARTED".equals(ev.getEventType()))
                .map(AgentRunEventMessage::getPayloadJson)
                .filter(p -> p != null && toolNamePattern(tool).matcher(p).find())
                .toList();
    }

    private static String toolFinishedPayload(List<AgentRunEventMessage> events, String tool) {
        return events.stream()
                .filter(ev -> "TOOL_CALL_FINISHED".equals(ev.getEventType()))
                .map(AgentRunEventMessage::getPayloadJson)
                .filter(p -> p != null && toolNamePattern(tool).matcher(p).find())
                .findFirst().orElse(null);
    }

    /** 服务端事件负载是带空格的 Jackson JSON（"tool_name": "x"），工具名匹配必须容忍空白。 */
    private static Pattern toolNamePattern(String tool) {
        return Pattern.compile("\"tool_name\"\\s*:\\s*\"" + Pattern.quote(tool) + "\"");
    }

    private static final Pattern SUCCESS_TRUE_PATTERN = Pattern.compile("\"success\"\\s*:\\s*true");

    private static final Pattern DATASET_IDS_PATTERN =
            Pattern.compile("\"dataset_ids\"\\s*:\\s*\"([^\"]*)\"");

    private static String extractDatasetIds(String payloadJson) {
        if (payloadJson == null) {
            return null;
        }
        Matcher m = DATASET_IDS_PATTERN.matcher(payloadJson);
        return m.find() ? m.group(1) : null;
    }

    private static boolean hasDigitToken(String datasetIds) {
        for (String token : datasetIds.split(",")) {
            if (token.trim().matches("\\d+")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDsToken(String datasetIds) {
        for (String token : datasetIds.split(",")) {
            if (token.trim().startsWith("ds_")) {
                return true;
            }
        }
        return false;
    }

    /** 附件/工件类键名的取值（filename、file_name、content_type、contentType、artifact_xxx 等）。 */
    private static final Pattern ATTACHMENT_VALUE_PATTERN = Pattern.compile(
            "\"[A-Za-z_]*(?:filename|file_name|content_type|contentType|artifact)[A-Za-z_]*\"\\s*:\\s*\"([^\"]*)\"");

    /** 从一段结构化 JSON 里收集值含 csv 的附件标记（去重；大小写不敏感按小写归一）。 */
    private static void collectCsvMarkers(String json, java.util.LinkedHashSet<String> out) {
        if (json == null || json.isBlank()) {
            return;
        }
        Matcher m = ATTACHMENT_VALUE_PATTERN.matcher(json);
        while (m.find()) {
            String value = m.group(1);
            if (value.toLowerCase().contains("csv")) {
                out.add(value);
            }
        }
    }

    private static String render(String template, Map<String, String> values) {
        String out = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        return out;
    }

    /** 代码块整体缩进两格，让它在提示词里成块。 */
    private static String indentCodeBlock(String code) {
        StringBuilder sb = new StringBuilder();
        for (String line : code.stripTrailing().split("\n", -1)) {
            sb.append("  ").append(line).append("\n");
        }
        return sb.toString();
    }

    private static String protoText(com.google.protobuf.Message msg) {
        StringBuilder sb = new StringBuilder();
        try {
            TextFormat.printer().escapingNonAscii(false).print(msg, sb);
        } catch (java.io.IOException e) {
            return String.valueOf(msg);
        }
        return sb.toString();
    }

    private static void writeText(Path path, String content) {
        try {
            Files.writeString(path, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("写证据文件失败 " + path + ": " + e);
        }
    }

    private void writeParams(Path outDir, Map<String, String> opts, String message, String pythonCode,
                             String stageConfigJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("==== 命令行参数 ====").append(System.lineSeparator());
        List<Map.Entry<String, String>> sorted = new ArrayList<>(opts.entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, String> e : sorted) {
            sb.append(e.getKey()).append("=").append(e.getValue()).append(System.lineSeparator());
        }
        sb.append(System.lineSeparator()).append("==== stage_config_json ====")
                .append(System.lineSeparator()).append(stageConfigJson).append(System.lineSeparator());
        sb.append(System.lineSeparator()).append("==== 提示词里的 Python 代码 ====")
                .append(System.lineSeparator()).append(pythonCode).append(System.lineSeparator());
        sb.append(System.lineSeparator()).append("==== 发送的完整提示词 message ====")
                .append(System.lineSeparator()).append(message).append(System.lineSeparator());
        writeText(outDir.resolve("params.txt"), sb.toString());
    }

    private void check(String name, boolean pass, String detail) {
        checks.add(new Check(name, pass, detail));
        System.out.println((pass ? "[PASS] " : "[FAIL] ") + name + " | " + detail);
    }

    private void info(String text) {
        System.out.println("[INFO] " + text);
    }

    private static String empty(String s) {
        return s == null ? "" : s;
    }

    private static String shorten(String s) {
        String one = s == null ? "" : s.replaceAll("\\s+", " ");
        return one.length() <= 300 ? one : one.substring(0, 300) + "...(截断)";
    }

    /** payload_json 为空时给个合法 JSON 字面量，保证 events.jsonl 每行可解析。 */
    private static String nvlJson(String json) {
        return json == null || json.isBlank() ? "{}" : json;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void sleep(long seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------------
    // 参数解析
    // ---------------------------------------------------------------------

    private static final List<String> VALUE_OPTS = List.of(
            "--agent-url", "--group", "--user", "--model", "--endpoint",
            "--ds-id", "--dataset-number", "--promote-number",
            "--name", "--description", "--semantic-ref", "--primary-key",
            "--coverage-scope", "--coverage-start", "--coverage-end",
            "--csv", "--out", "--run-id", "--message-file",
            "--timeout-seconds", "--poll-interval-seconds", "--cancel-after-seconds",
            "--lane-tag", "--mod-csv-gate");

    private static final List<String> FLAG_OPTS = List.of(
            "--capture-llm", "--no-tool-gate", "--expect-degraded", "--mod-gate",
            "--generate-artifacts", "--artifacts-probe", "--help");

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (FLAG_OPTS.contains(a)) {
                opts.put(a, "1");
            } else if (VALUE_OPTS.contains(a)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(a + " 缺少取值");
                }
                opts.put(a, args[++i]);
            } else {
                throw new IllegalArgumentException("不认识的参数: " + a + "（看 --help）");
            }
        }
        return opts;
    }

    private static void printUsage() {
        System.out.println("""
                014-data-04 run 驱动器（c6/c7/c8）：Dubbo tri 直连泳道 agent，创建并跟踪一条真实 run。

                连接与身份：
                  --agent-url <url>      泳道 agentLangchainService 直连地址（默认 tri://172.27.49.47:53400）
                  --lane-tag <tag>       泳道标签：给每次出站调用带官方 dubbo.tag 附件，createRun 落库
                                         lane_tag 用（直连必须带，缺了 run 行 lane_tag 为空、租约续不上）
                  --group <g>            Dubbo group（默认 langchain，agentLangchainService 固定值）
                  --user <id>            直通用户 id，直接进请求 user_id（默认 5）
                  --model <m>            模型名，写入 stage_config_json 覆盖服务端默认（默认 deepseek/deepseek-v4.1-flash）
                  --endpoint <e>         端点名（默认 openrouter）

                场景参数（填进提示词）：
                  --ds-id <id>           ds_ 平台对象 id（创建模式必填）
                  --dataset-number <n>   【已废弃】新模板里编号由模型按第二步结果自定；参数保留只为旧脚本不报错，传入被忽略
                  --promote-number <n>   【已废弃】同上
                  --name/--description/--semantic-ref/--primary-key/--coverage-scope
                                         promote 其余必填参数（semantic-ref 形如 name@version，须为 e2e phase 5 登记的定义）
                  --coverage-start/--coverage-end
                                         【已废弃】覆盖日期改由模型按第四步取数应答的实际值传入；参数保留只为旧脚本不报错
                  --csv <path>           可选，本地 CSV，用来预读期望数据行数做软核对
                  --message-file <path>  可选，整段提示词直接用文件内容（不替换占位符）

                流程控制：
                  --run-id <id>          挂已有 run（不创建，只轮询+取证）
                  --timeout-seconds <n>  轮询超时（默认 900）
                  --poll-interval-seconds <n> 轮询间隔（默认 5）
                  --out <dir>            evidence 输出根目录（默认 out-014data04）
                  --capture-llm          置 capture_llm_requests=true（默认关）
                  --no-tool-gate         关工具核对门（退出码只看终态，不要求三工具齐全）
                  --cancel-after-seconds <n> C3 取消路径：轮询超 n 秒仍未终态时发一次 cancelRun
                                         （打 CANCEL_SENT，失败打 CANCEL_FAILED 且不重发），随后照常轮询到终态（默认 0=关）
                  --generate-artifacts   创建 run 时置 generate_artifacts=true（014-new-03 产物通道开关，默认关）
                  --artifacts-probe      run 终态后产物探针（014-new-03）：清单全文落 artifacts-list.txt，
                                         run_output 逐个下载到 artifacts/ 并记 SHA-256 到 artifacts-sha256.txt，
                                         非 run_output 只记清单不下载；探针尽力而为，失败不改退出码
  --expect-degraded      c9 停机窗口模式：提示词换五步降级探测模板，核对门按
                         「预期失败即 PASS」判（executePython=PLATFORM_OBJECT_UNAVAILABLE、
                         promote=TOOL_ERROR；混传与 promote 成功两项不再检查）
  --mod-gate             014-data-05 phase 2 修改任务模式（配 --run-id 用）：run 由 05 受理端点
                         创建，驱动器只跟踪+取证；核对门换成 05 门（executePython 发生 +
                         结构化负载里去重 csv 附件标记恰一个）；04 专属核对全部跳过；
                         run 终态词表仍是 COMPLETED（task 侧 SUCCEEDED 由 05 e2e 断言）
  --mod-csv-gate <hard|off> 05 的 csv 附件门开关（默认 hard；off=只记录不断言，
                         run 侧负载若不暴露附件信息时降级用，登记段权威计数在 05 e2e 侧）

                提示词模板与 Python 代码在源码 RunDriverMain 顶部常量，交付评审时直接改。

                退出码：0=COMPLETED 且工具核对全过；1=COMPLETED 但工具核对有 FAIL；
                        2=连接/创建/轮询中断等基础设施错误；3=终态但不是 COMPLETED；4=超时未到终态。

                evidence（out/<时间戳>/ 目录）：params.txt（参数+完整提示词+模型配置原文）、
                create-response.txt、poll.log、events.jsonl（事件全量原文）、result.txt（应答+markdown 原文）、
                run-message.txt（run 详情，ext 含 stage_config_json）、run-credits.txt、evidence-summary.txt；
                --artifacts-probe 时另有 artifacts-list.txt（产物清单全文+探针失败原文）、
                artifacts-sha256.txt（下载账）与 artifacts/（run_output 产物原件）。
                """);
    }
}
