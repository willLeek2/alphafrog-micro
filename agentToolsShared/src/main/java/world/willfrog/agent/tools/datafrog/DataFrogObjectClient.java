package world.willfrog.agent.tools.datafrog;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;
import world.willfrog.datafrog.idl.DataObjectCard;
import world.willfrog.datafrog.idl.DataObjectService;
import world.willfrog.datafrog.idl.GetDataObjectRequest;
import world.willfrog.datafrog.idl.GetDataObjectResponse;
import world.willfrog.datafrog.idl.ListDataObjectsRequest;
import world.willfrog.datafrog.idl.ListDataObjectsResponse;
import world.willfrog.datafrog.idl.ReadObjectDataRequest;
import world.willfrog.datafrog.idl.ReadObjectDataResponse;
import world.willfrog.datafrog.idl.RegisterDataObjectRequest;
import world.willfrog.datafrog.idl.RegisterDataObjectResponse;
import world.willfrog.datafrog.idl.ResultInfo;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 主仓库 → DataFrog 数据对象平台的 Dubbo 消费端（014-data-04）。
 *
 * <p>主仓库的第二个 Dubbo group：经 group=datafrog 引用 {@link DataObjectService}
 * （写法照 DataFrog 侧 AgentRunClient 消费主仓库 group=langchain 的先例；check=false，
 * DataFrog 未部署时启动不失败，调用期才报错）。封装四件事：
 * <ul>
 *   <li>{@link #listMyDataObjects(String)}：枚举当前用户自己的平台对象（014-data-04 起
 *       ListDataObjects 带服务端 filter_owner 等值过滤，不再全量拉回内存过滤——bob 节点 c
 *       裁定 d61398da）</li>
 *   <li>{@link #getDataObject(String)} / {@link #registerDataObject(RegisterDataObjectRequest)}：直封装</li>
 *   <li>{@link #readAllObjectData(String, int, Path)}：ReadObjectData 字节分页拉全量 part，
 *       按版本行 content_checksum 做 fail-closed 校验</li>
 *   <li>{@link #parquetToCsv(List, Path)}：DuckDB JDBC 把 part 文件集合转成单份 CSV</li>
 * </ul>
 *
 * <p>fail-closed 口径：拼装出的字节流 sha256 与登记卡 content_checksum 不一致即抛
 * {@link ChecksumMismatchException}，宁可失败也不把错文件挂进沙箱。业务失败（ResultInfo
 * ok=false）统一转 IllegalStateException，报文带错误码与 message。
 */
@Component
@Slf4j
public class DataFrogObjectClient {

    /** ListDataObjects 单页大小（服务端上限 200，取满页减少往返）。 */
    private static final int LIST_PAGE_SIZE = 200;
    /** ListDataObjects 翻页保护上限：正常用户对象量远到不了，防游标异常导致死循环。 */
    private static final int MAX_LIST_PAGES = 1000;
    /** ReadObjectData 请求段大小：1MB（服务端按上限钳制，超了取上限值）。 */
    private static final int READ_PAGE_SIZE_BYTES = 1024 * 1024;
    /** ReadObjectData 分页保护上限（约 200GB），防 next_page_token 异常时无限拉取。 */
    private static final int MAX_READ_PAGES = 200_000;

    /** 主仓库消费的第二个 Dubbo group（先例：DataFrog 侧 AgentRunClient 消费 group=langchain）。 */
    @DubboReference(group = "datafrog", check = false)
    private DataObjectService dataObjectService;

    /** readAllObjectData 的产出：登记卡 + 实际读取的版本号 + 按 part 文件名升序的 part 文件。 */
    public record ObjectContent(DataObjectCard card, int version, List<Path> partFiles) {
    }

    /** 拼装字节流的 sha256 与版本行 content_checksum 不一致（fail-closed，宁可失败不挂错文件）。 */
    public static final class ChecksumMismatchException extends IllegalStateException {
        private final String expected;
        private final String actual;

        public ChecksumMismatchException(String expected, String actual) {
            super("平台对象内容校验不一致（fail-closed 拒绝挂载）: expected=" + expected + " actual=" + actual);
            this.expected = expected;
            this.actual = actual;
        }

        public String expected() {
            return expected;
        }

        public String actual() {
            return actual;
        }
    }

    /**
     * 枚举当前用户自己的平台数据对象。
     *
     * <p>014-data-04 起 ListDataObjects 请求带服务端 filter_owner 等值过滤（rpc 新字段，
     * 空=01 原全量分页行为；bob 节点 c 裁定 d61398da：不做「全量拉回进程内存过滤」）。
     * 翻页循环与保护上限保留：同一过滤条件内接着翻页，next_page_token 为空即结束。
     * 边界不变：列表只查当前用户自己的对象，owner 固定取当前登录用户（由调用方从
     * AgentContext 取得传入），不接受外部传入任意 owner。
     */
    public List<DataObjectCard> listMyDataObjects(String currentUser) {
        if (currentUser == null || currentUser.isBlank()) {
            return List.of();
        }
        List<DataObjectCard> mine = new ArrayList<>();
        String pageToken = "";
        for (int page = 0; page < MAX_LIST_PAGES; page++) {
            ListDataObjectsRequest.Builder builder = ListDataObjectsRequest.newBuilder()
                    .setPageSize(LIST_PAGE_SIZE)
                    .setFilterOwner(currentUser);
            if (!pageToken.isEmpty()) {
                builder.setPageToken(pageToken);
            }
            ListDataObjectsResponse response = dataObjectService.listDataObjects(builder.build());
            requireOk(response.getResult(), "ListDataObjects");
            mine.addAll(response.getCardsList());
            pageToken = response.getNextPageToken();
            if (pageToken == null || pageToken.isEmpty()) {
                return mine;
            }
        }
        throw new IllegalStateException("ListDataObjects 翻页超过保护上限，疑似分页游标异常");
    }

    /** 读取登记卡；业务失败（ok=false）抛 IllegalStateException，报文带错误码与 message。 */
    public DataObjectCard getDataObject(String objectId) {
        GetDataObjectResponse response = dataObjectService.getDataObject(
                GetDataObjectRequest.newBuilder().setObjectId(objectId).build());
        requireOk(response.getResult(), "GetDataObject");
        return response.getCard();
    }

    /** RegisterDataObject 直封装：应答里带 ResultInfo，由调用方判定成败（promote 工具用）。 */
    public RegisterDataObjectResponse registerDataObject(RegisterDataObjectRequest request) {
        return dataObjectService.registerDataObject(request);
    }

    /**
     * 把一个平台对象某版本的全部内容拉成本地 part 文件集合（ReadObjectData 字节分页）。
     *
     * <p>流程：GetDataObject 拿卡（version 空或 0 解析为当前 ACTIVE 版本 current_version，
     * 后续分页显式传这个版本号，避免中途追加导致跨版本混读）→ 循环 ReadObjectData
     * （page_size 1MB）：应答一页不跨 part，字节按顺序写当前 part 文件，part_index 变化
     * 即封当前文件开新文件（part-%06d.parquet 按文件名升序，与 content_checksum 的拼接
     * 定义同序）；同时维护全流 sha256 → next_page_token 为空即读完 → 最终 sha256 与卡上
     * content_checksum 比对，不一致抛 {@link ChecksumMismatchException}（fail-closed）。
     *
     * @param objectId 平台对象 id（ds_ 前缀）
     * @param version  目标版本；空或 0 表示当前 ACTIVE 版本
     * @param workDir  part 文件落盘目录（方法内创建；调用方负责后续清理）
     * @return 登记卡、实际读取的版本号、按文件名升序的 part 文件列表
     */
    public ObjectContent readAllObjectData(String objectId, int version, Path workDir) {
        DataObjectCard card = getDataObject(objectId);
        int effectiveVersion = version > 0 ? version : card.getCurrentVersion();
        if (effectiveVersion <= 0) {
            throw new IllegalStateException("平台对象没有可读的 ACTIVE 版本: " + objectId);
        }
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            throw new IllegalStateException("创建 part 工作目录失败: " + workDir, e);
        }

        MessageDigest digest = sha256();
        List<Path> partFiles = new ArrayList<>();
        OutputStream currentPart = null;
        int currentPartIndex = -1;
        String pageToken = "";
        try {
            for (int page = 0; page < MAX_READ_PAGES; page++) {
                ReadObjectDataRequest.Builder builder = ReadObjectDataRequest.newBuilder()
                        .setObjectId(objectId)
                        .setVersion(effectiveVersion)
                        .setPageSize(READ_PAGE_SIZE_BYTES);
                if (!pageToken.isEmpty()) {
                    builder.setPageToken(pageToken);
                }
                ReadObjectDataResponse response = dataObjectService.readObjectData(builder.build());
                requireOk(response.getResult(), "ReadObjectData");
                ByteString data = response.getData();
                if (response.getPartIndex() < currentPartIndex) {
                    // part 序号按文件名升序单调递增；回退说明服务端或网络出了问题，fail-closed。
                    throw new IllegalStateException("ReadObjectData part 序号非单调递增: "
                            + currentPartIndex + " -> " + response.getPartIndex());
                }
                if (!data.isEmpty()) {
                    // 一页不跨 part：part_index 变化即进入新 part，封当前文件开新文件。
                    if (currentPart == null || response.getPartIndex() != currentPartIndex) {
                        if (currentPart != null) {
                            currentPart.close();
                        }
                        currentPartIndex = response.getPartIndex();
                        Path partFile = workDir.resolve(String.format("part-%06d.parquet", currentPartIndex));
                        currentPart = Files.newOutputStream(partFile);
                        partFiles.add(partFile);
                    }
                    byte[] chunk = data.toByteArray();
                    currentPart.write(chunk);
                    digest.update(chunk);
                }
                pageToken = response.getNextPageToken() == null ? "" : response.getNextPageToken();
                if (pageToken.isEmpty()) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("写平台对象 part 文件失败: " + objectId, e);
        } finally {
            closeQuietly(currentPart);
        }
        if (!pageToken.isEmpty()) {
            throw new IllegalStateException("ReadObjectData 分页超过保护上限，疑似游标异常: " + objectId);
        }
        if (partFiles.isEmpty()) {
            throw new IllegalStateException("平台对象没有读到任何内容字节: " + objectId);
        }

        // fail-closed：拼接字节流的 sha256 必须等于版本行 content_checksum，不一致拒绝挂载。
        String actual = toHex(digest.digest());
        String expected = card.getContentChecksum();
        if (expected == null || expected.isBlank() || !expected.equalsIgnoreCase(actual)) {
            throw new ChecksumMismatchException(expected == null ? "" : expected, actual);
        }
        log.info("平台对象读取完成并通过校验和: object={} version={} parts={} bytes-sha256={}",
                objectId, effectiveVersion, partFiles.size(), actual);
        return new ObjectContent(card, effectiveVersion, partFiles);
    }

    /**
     * 把 part 文件集合转成单份 CSV（DuckDB JDBC）。
     *
     * <p>写法对齐 DataFrog 侧 ContentStorageService.exportCsv：read_parquet([...part 文件按
     * 文件名升序...]) 全列直读（DataFrog 落盘时全列 VARCHAR，同款引擎同版本读回，口径一致），
     * COPY 出带表头的逗号分隔 CSV。校验和只对 parquet part 字节算，不算这份 CSV。
     */
    public void parquetToCsv(List<Path> partFiles, Path outFile) {
        if (partFiles == null || partFiles.isEmpty()) {
            throw new IllegalStateException("没有 part 文件，无法转 CSV");
        }
        try {
            Files.createDirectories(outFile.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("创建 CSV 输出目录失败: " + outFile.getParent(), e);
        }
        String sql = "COPY (SELECT * FROM read_parquet(" + parquetList(partFiles) + ")) TO "
                + sqlString(outFile) + " (FORMAT CSV, HEADER, DELIMITER ',')";
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = conn.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("parquet 转 CSV 失败: " + e.getMessage(), e);
        }
    }

    private static void requireOk(ResultInfo result, String rpcName) {
        if (result == null || !result.getOk()) {
            String code = result == null ? "NULL_RESULT" : result.getCode().name();
            String message = result == null ? "" : result.getMessage();
            throw new IllegalStateException(rpcName + " 业务失败: code=" + code + " message=" + message);
        }
    }

    private static String parquetList(List<Path> parts) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(sqlString(parts.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String sqlString(Path p) {
        return "'" + p.toString().replace("'", "''") + "'";
    }

    private static void closeQuietly(OutputStream out) {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // 关闭失败不掩盖读取主流程的异常
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
