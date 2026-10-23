package world.willfrog.alphafrogmicro.domestic.stock.service;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一 SQL 资产取数工具的服务端守卫（方案 D4 八步逐条实现），输入 SQL 文本，
 * 输出放行（附带可执行 SQL 与强制后的外层 LIMIT）或结构化拒绝（稳定错误码＋中文说明）。
 *
 * <p>八步与方法的对应关系：</p>
 * <ol>
 *   <li>单语句——在剥字符串与注释后的骨架上判分号（{@link #checkSingleStatement}，
 *       骨架算法参照 agentToolsShared 的 duckdb_query_runner.py）。</li>
 *   <li>必须以 SELECT 或 WITH 开头（{@link #checkStartsSelect}）。</li>
 *   <li>关键字黑名单 INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|TRUNCATE|GRANT|REVOKE|
 *       EXEC|EXECUTE|COPY|VACUUM|MERGE|SET|RESET|CALL|DO|INTO（{@link #checkKeywords}，
 *       在 remote_pg_query 黑名单上补 SET/RESET/CALL/DO；INTO 为 CR 补充——SELECT…INTO
 *       写路径，合法分析 SQL 不会裸用）。</li>
 *   <li>FROM/JOIN 关系名逐一对照 35 表显式白名单（{@link #checkRelations}）；只认裸表名，
 *       任何 schema 限定（information_schema./pg_catalog./public. 等一切 {@code x.y} 形态）
 *       一律拒；WITH 定义的 CTE 名收集为本地关系放行（含嵌套子查询里的 WITH）。</li>
 *   <li>函数族黑名单（{@link #checkFunctions}）：schema 限定调用全拒；裸名命中
 *       pg_/lo_/dblink/cursor_to_/xml_ 前缀、含 _to_xml、set_config/current_setting 即拒；
 *       FROM/JOIN 里的函数表源由同一次全文扫描统一核。</li>
 *   <li>外层 LIMIT/OFFSET 解析（{@link #resolveOuterLimit}）：无外层 LIMIT 拒
 *       MISSING_LIMIT（不做隐式默认值，避免模型误以为拿到全量）；LIMIT 超硬上限改写
 *       并标记；OFFSET 原样保留（分页建议写在说明文件，守卫不设硬限，超时兜底）。</li>
 *   <li>af_date('YYYY-MM-DD') 日期辅助函数（{@link #replaceAfDateLiterals}）：在骨架位
 *       映射上把函数调用整体替换为上海零点毫秒的纯数字字面量（复用
 *       DateConvertUtils.convertDateStrToLong 的语义），只替换函数调用位、不碰字面量内容；
 *       替换后重走一遍第 1-5 条骨架校验（防字面量内误伤与语法注入）。</li>
 *   <li>执行参数（只读事务＋超时＋行/字节双硬上限）在 {@link AssetSqlExecutor} 落地。</li>
 * </ol>
 *
 * <p>已知取舍（与参考实现 duckdb_query_runner 一致）：骨架不识别 PG 的 dollar 引用字符串
 * （{@code $$...$$}），其内的分号/关键字会被当作代码检查——方向是误拒而不是漏放，
 * 模型面向的说明文件统一用单引号字面量，不构成可用性问题。</p>
 *
 * <p>双引号标识符一律拒（CR 必改 1）：把双引号标识符剥成空白会让关系扫描与函数检查
 * 全部失明（{@code FROM "public"."alphafrog_user"}、{@code SELECT "pg_sleep"(1) LIMIT 5}
 * 等形态穿透），而模型面向的常规分析 SQL 完全不需要双引号——骨架构建时检出即拒
 * FORBIDDEN_SYNTAX，引导用裸表名/裸列名/裸函数名重写。</p>
 */
@Component
public class AssetSqlGuard {

    // ---- 稳定错误码（跨 RPC 可机读，经响应级 error 结构返回，不靠异常传递） ----
    public static final String CODE_MULTI_STATEMENT = "MULTI_STATEMENT";
    public static final String CODE_NOT_SELECT = "NOT_SELECT";
    public static final String CODE_FORBIDDEN_KEYWORD = "FORBIDDEN_KEYWORD";
    public static final String CODE_TABLE_NOT_ALLOWED = "TABLE_NOT_ALLOWED";
    public static final String CODE_FORBIDDEN_FUNCTION = "FORBIDDEN_FUNCTION";
    public static final String CODE_FORBIDDEN_SYNTAX = "FORBIDDEN_SYNTAX";
    public static final String CODE_MISSING_LIMIT = "MISSING_LIMIT";
    public static final String CODE_INVALID_DATE_LITERAL = "INVALID_DATE_LITERAL";

    // ---- 关键字黑名单（方案 D4 第 3 条的 18 词＋CR 补充 INTO——SELECT…INTO 写路径，
    //      合法分析 SQL 不会裸用该词，readOnly 事务之外再加一道结构化拒绝） ----
    private static final List<String> FORBIDDEN_KEYWORDS = List.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "CREATE", "ALTER", "TRUNCATE",
            "GRANT", "REVOKE", "EXEC", "EXECUTE", "COPY", "VACUUM", "MERGE",
            "SET", "RESET", "CALL", "DO", "INTO");

    private static final int CASE_INSENSITIVE = Pattern.CASE_INSENSITIVE;

    private static final Pattern STARTS_SELECT_OR_WITH = Pattern.compile("^\\s*(select|with)\\b", CASE_INSENSITIVE);
    private static final Pattern FROM_OR_JOIN = Pattern.compile("\\b(from|join)\\b", CASE_INSENSITIVE);
    private static final Pattern WITH_KEYWORD = Pattern.compile("\\bwith\\b", CASE_INSENSITIVE);
    private static final Pattern LIMIT_CLAUSE = Pattern.compile("\\blimit\\s+([0-9]+)", CASE_INSENSITIVE);
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");
    private static final Pattern IDENTIFIER_CALL = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_$]*)\\s*\\(");
    private static final Pattern AF_DATE_CALL = Pattern.compile("\\baf_date\\s*\\(", CASE_INSENSITIVE);
    private static final Pattern AF_DATE_BLANK_LITERAL_CALL = Pattern.compile("\\baf_date\\s*\\(\\s*\\)", CASE_INSENSITIVE);
    private static final Pattern QUOTED_ISO_DATE = Pattern.compile("'([0-9]{4})-([0-9]{2})-([0-9]{2})'");

    /** 关系名之后不该被当成别名跳过的子句/连接关键字（「as」单独处理，不在集合内） */
    private static final Set<String> CLAUSE_KEYWORDS = Set.of(
            "where", "group", "order", "limit", "offset", "union", "intersect", "except",
            "join", "on", "using", "inner", "left", "right", "full", "cross", "natural",
            "having", "fetch", "for", "window", "lateral");

    /** LIMIT 硬上限的配置键与默认值（方案 D3：默认 20 万行，可配置覆盖） */
    static final String MAX_ROWS_PROP = "asset-sql.max-rows";
    private static final long DEFAULT_MAX_ROWS = 200_000L;

    private final long maxRows;

    public AssetSqlGuard(Environment environment) {
        this.maxRows = environment.getProperty(MAX_ROWS_PROP, Long.class, DEFAULT_MAX_ROWS);
    }

    /**
     * 守卫主入口：八步全过返回放行判定（executableSql＝af_date 替换与 LIMIT 改写后的可执行文本，
     * enforcedLimit＝强制后的外层 LIMIT，limitRewritten＝LIMIT 是否被改写到硬上限）；
     * 任一步不过返回带稳定错误码的结构化拒绝。
     */
    public GuardVerdict inspect(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            return GuardVerdict.reject(CODE_NOT_SELECT,
                    "查询为空，必须以 SELECT 或 WITH 开头的单条只读查询", "");
        }
        // 第一遍：第 1-5 条骨架校验（在原文骨架上做）
        Skeleton first = buildSkeleton(rawSql);
        try {
            checkNoQuotedIdentifiers(first);
            checkSingleStatement(first);
            checkStartsSelect(first);
            checkKeywords(first);
            checkRelations(first);
            checkFunctions(first);
        } catch (Rejection rejection) {
            return rejection.verdict();
        }
        // 第 7 条：af_date 字面量替换（骨架位映射，只替换函数调用位）
        String replacedSql;
        try {
            replacedSql = replaceAfDateLiterals(rawSql, first);
        } catch (Rejection rejection) {
            return rejection.verdict();
        }
        // 第 7 条后半：替换后重走一遍第 1-5 条骨架校验
        Skeleton second = buildSkeleton(replacedSql);
        try {
            checkNoQuotedIdentifiers(second);
            checkSingleStatement(second);
            checkStartsSelect(second);
            checkKeywords(second);
            checkRelations(second);
            checkFunctions(second);
        } catch (Rejection rejection) {
            return rejection.verdict();
        }
        // 第 6 条：外层 LIMIT/OFFSET 解析与改写（在替换后的最终文本上做，位置才与可执行文本一致）
        try {
            return resolveOuterLimit(replacedSql, second);
        } catch (Rejection rejection) {
            return rejection.verdict();
        }
    }

    // ==================== 第 0 条：双引号标识符一律拒（CR 必改 1） ====================

    /**
     * 双引号标识符（{@code "name"}，含函数名/表名/列名的引号形态）一律拒：骨架会把它们
     * 剥成空白，所有检查器随之失明——引号形态既能让任意表绕过白名单（含 schema 限定），
     * 也能让函数族黑名单失效（PG 正常解析执行引号函数名）。常规分析 SQL 用裸名即可，
     * 零可用性损失。字符串字面量内部的单引号场景不受影响（字面量先被剥成空白）。
     */
    private void checkNoQuotedIdentifiers(Skeleton skeleton) {
        if (skeleton.hasQuotedIdentifier()) {
            throw new Rejection(GuardVerdict.reject(CODE_FORBIDDEN_SYNTAX,
                    "检测到双引号标识符：本工具只接受裸表名、裸列名与裸函数名，请去掉双引号重写"
                            + "（引号形态会让白名单与函数族检查失效，一律拒绝）",
                    details("reason", "double_quoted_identifier")));
        }
    }

    // ==================== 第 1 条：单语句 ====================

    private void checkSingleStatement(Skeleton skeleton) {
        String[] parts = skeleton.text().split(";");
        int statements = 0;
        for (String part : parts) {
            if (!part.isBlank()) {
                statements++;
            }
        }
        if (statements > 1) {
            throw new Rejection(GuardVerdict.reject(CODE_MULTI_STATEMENT,
                    "只允许单条查询语句（分号分隔的多语句会被拒绝）",
                    details("statements", statements)));
        }
    }

    // ==================== 第 2 条：SELECT / WITH 开头 ====================

    private void checkStartsSelect(Skeleton skeleton) {
        if (!STARTS_SELECT_OR_WITH.matcher(skeleton.text()).find()) {
            throw new Rejection(GuardVerdict.reject(CODE_NOT_SELECT,
                    "查询必须以 SELECT 或 WITH 开头（本工具只允许只读查询）", ""));
        }
    }

    // ==================== 第 3 条：关键字黑名单 ====================

    private void checkKeywords(Skeleton skeleton) {
        for (String keyword : FORBIDDEN_KEYWORDS) {
            if (Pattern.compile("\\b" + keyword + "\\b", CASE_INSENSITIVE).matcher(skeleton.text()).find()) {
                throw new Rejection(GuardVerdict.reject(CODE_FORBIDDEN_KEYWORD,
                        "查询包含被禁止的关键字 " + keyword + "（本工具只允许只读查询）",
                        details("keyword", keyword)));
            }
        }
    }

    // ==================== 第 4 条：表白名单＋CTE（含第 5 条的函数表源） ====================

    /**
     * 全文扫描所有 FROM/JOIN 的关系引用（覆盖子查询，做法同 remote_pg_query 的 sqlGuard.ts，
     * 但修掉其「CTE 名被白名单误拒」的弱点）：关系名必须在 35 表白名单或 CTE 名集合内；
     * {@code x.y} 形态的 schema 限定引用一律拒；名字后紧跟左括号的是函数表源，
     * 交给 {@link #checkFunctions} 的全文函数扫描统一核（generate_series 等裸名放行）。
     */
    private void checkRelations(Skeleton skeleton) {
        Set<String> cteNames = collectCteNames(skeleton);
        Matcher matcher = FROM_OR_JOIN.matcher(skeleton.text());
        while (matcher.find()) {
            int pos = matcher.end();
            while (true) {
                pos = skipWhitespace(skeleton, pos);
                if (pos >= skeleton.length()) {
                    break;
                }
                char c = skeleton.charAt(pos);
                if (c == '(') {
                    // 子查询 / LATERAL 括号体：跳过括号体本身，其内部的 FROM/JOIN 由本全文扫描另行命中；
                    // 括号体剥掉冗余括号后恰是单个裸标识符时（FROM (alphafrog_user) u 等形态），
                    // PG 解析期才拒绝的结构化拒绝缺口在此补上：同样对照白名单（CR 建议①）。
                    int closeEnd = skipBalancedParentheses(skeleton, pos);
                    String bare = bareIdentifierInParens(skeleton, pos, closeEnd);
                    if (bare != null
                            && !AssetSqlAllowedTables.ALLOWED_TABLES.contains(bare)
                            && !cteNames.contains(bare)) {
                        throw new Rejection(GuardVerdict.reject(CODE_TABLE_NOT_ALLOWED,
                                "关系 " + bare + " 不在资产表白名单内（白名单共 35 张资产表，可用 listAssetTables 查看全部表）",
                                details("relation", bare, "reason", "paren_wrapped")));
                    }
                    pos = closeEnd;
                } else if (isIdentifierStart(c)) {
                    int[] identRange = readIdentifier(skeleton, pos);
                    String name = skeleton.text().substring(identRange[0], identRange[1])
                            .toLowerCase(Locale.ROOT);
                    int after = skipWhitespace(skeleton, identRange[1]);
                    if (after < skeleton.length() && skeleton.charAt(after) == '(') {
                        // 函数表源（如 generate_series(...)）：不在关系白名单里核，函数族规则统一核
                        pos = skipBalancedParentheses(skeleton, after);
                    } else if (after < skeleton.length() && skeleton.charAt(after) == '.') {
                        // schema 限定关系引用（含 information_schema./pg_catalog./public. 及一切 x.y）：一律拒
                        String qualified = name;
                        int memberStart = skipWhitespace(skeleton, after + 1);
                        if (memberStart < skeleton.length() && isIdentifierStart(skeleton.charAt(memberStart))) {
                            qualified = name + "." + skeleton.text()
                                    .substring(memberStart, readIdentifier(skeleton, memberStart)[1])
                                    .toLowerCase(Locale.ROOT);
                        }
                        throw new Rejection(GuardVerdict.reject(CODE_TABLE_NOT_ALLOWED,
                                "不允许 schema 限定的关系引用（" + qualified + "），只接受白名单内的裸表名",
                                details("relation", qualified, "reason", "schema_qualified")));
                    } else {
                        if (!AssetSqlAllowedTables.ALLOWED_TABLES.contains(name) && !cteNames.contains(name)) {
                            throw new Rejection(GuardVerdict.reject(CODE_TABLE_NOT_ALLOWED,
                                    "关系 " + name + " 不在资产表白名单内（白名单共 35 张资产表，可用 listAssetTables 查看全部表）",
                                    details("relation", name)));
                        }
                        pos = identRange[1];
                    }
                } else {
                    // 解析不出关系名（非法形态）：跳过，交给后续检查与 PG 本身（双引号标识符
                    // 已在骨架期被 checkNoQuotedIdentifiers 拒掉，不再进入这里）
                    break;
                }
                // 跳过可选别名后看是否逗号续列表（FROM a x, b y / FROM (…) t, b）
                pos = skipAlias(skeleton, pos);
                pos = skipWhitespace(skeleton, pos);
                if (pos < skeleton.length() && skeleton.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                break;
            }
        }
    }

    /**
     * 收集 WITH 定义的 CTE 名（方案 D4 第 4 条必改项）：含 WITH RECURSIVE、列清单
     * （WITH t(a,b) AS …）与逗号连续定义；嵌套在子查询里的 WITH 也一并收集
     * （全文找 with 关键字）。CTE 定义体内部引用的表仍被上面的全文 FROM/JOIN 扫描覆盖。
     */
    private Set<String> collectCteNames(Skeleton skeleton) {
        Set<String> names = new HashSet<>();
        Matcher matcher = WITH_KEYWORD.matcher(skeleton.text());
        while (matcher.find()) {
            int pos = skipWhitespace(skeleton, matcher.end());
            if (matchesWord(skeleton, pos, "recursive")) {
                pos = skipWhitespace(skeleton, pos + "recursive".length());
            }
            while (pos >= 0 && pos < skeleton.length() && isIdentifierStart(skeleton.charAt(pos))) {
                int[] identRange = readIdentifier(skeleton, pos);
                names.add(skeleton.text().substring(identRange[0], identRange[1]).toLowerCase(Locale.ROOT));
                int p = skipWhitespace(skeleton, identRange[1]);
                if (p < skeleton.length() && skeleton.charAt(p) == '(') {
                    // CTE 列清单：WITH t(a, b) AS (…)
                    p = skipWhitespace(skeleton, skipBalancedParentheses(skeleton, p));
                }
                if (!matchesWord(skeleton, p, "as")) {
                    break;
                }
                p = skipWhitespace(skeleton, p + "as".length());
                if (p >= skeleton.length() || skeleton.charAt(p) != '(') {
                    break;
                }
                p = skipWhitespace(skeleton, skipBalancedParentheses(skeleton, p));
                if (p < skeleton.length() && skeleton.charAt(p) == ',') {
                    pos = skipWhitespace(skeleton, p + 1);
                    continue;
                }
                break;
            }
        }
        return names;
    }

    // ==================== 第 5 条：函数族黑名单 ====================

    /**
     * 全文扫描函数调用形态（标识符后紧跟左括号），覆盖 SELECT 表达式、FROM/JOIN 函数表源
     * 与一切位置的调用。两类拒绝（方案 D4 第 5 条）：schema 限定调用全拒（正常分析 SQL
     * 用裸函数名即可）；裸名命中函数族黑名单即拒。函数族按「族」默认拒绝、不逐个枚举特例：
     * 前缀 pg_（管理/文件/进程/锁全族——守卫执行账号与资产表写方是同一库账号，
     * pg_terminate_backend 等能杀写连接，readOnly 事务只挡写、挡不住这些非写管理操作）、
     * 前缀 lo_（大对象）、前缀 dblink（跨库）、前缀 cursor_to_、名字含 _to_xml 或前缀
     * xml_（XML 族可内嵌 SQL 文本执行，query_to_xml('SELECT…') 会绕过关系白名单）、
     * set_config/current_setting（会话配置写/读，set_config('statement_timeout','0')
     * 会绕过 SET 黑名单关掉超时）。
     */
    private void checkFunctions(Skeleton skeleton) {
        Matcher matcher = IDENTIFIER_CALL.matcher(skeleton.text());
        while (matcher.find()) {
            String name = matcher.group(1).toLowerCase(Locale.ROOT);
            int before = skipWhitespaceBackward(skeleton, matcher.start(1));
            if (before >= 0 && skeleton.charAt(before) == '.') {
                throw new Rejection(GuardVerdict.reject(CODE_FORBIDDEN_FUNCTION,
                        "不允许 schema 限定的函数调用（" + name + "），请使用裸函数名",
                        details("function", name, "family", "schema_qualified")));
            }
            String family = forbiddenFunctionFamily(name);
            if (family != null) {
                throw new Rejection(GuardVerdict.reject(CODE_FORBIDDEN_FUNCTION,
                        "函数 " + name + " 属于被禁止的函数族：" + family,
                        details("function", name, "family", family)));
            }
        }
    }

    /** 返回命中的族别说明；未命中返回 null（放行）。 */
    private String forbiddenFunctionFamily(String lowerName) {
        if (lowerName.startsWith("pg_")) {
            return "pg_ 前缀（管理/文件/进程/锁函数族）";
        }
        if (lowerName.startsWith("lo_")) {
            return "lo_ 前缀（大对象函数族）";
        }
        if (lowerName.startsWith("dblink")) {
            return "dblink 前缀（跨库函数族）";
        }
        if (lowerName.startsWith("cursor_to_")) {
            return "cursor_to_ 前缀函数族";
        }
        if (lowerName.contains("_to_xml")) {
            return "_to_xml（XML 族，可内嵌 SQL 文本执行）";
        }
        if (lowerName.startsWith("xml_")) {
            return "xml_ 前缀（XML 函数族）";
        }
        if (lowerName.equals("set_config") || lowerName.equals("current_setting")) {
            return "会话配置写/读（set_config/current_setting）";
        }
        return null;
    }

    // ==================== 第 7 条：af_date 字面量替换 ====================

    /**
     * 把 {@code af_date('YYYY-MM-DD')} 整体替换为上海零点毫秒的纯数字字面量。
     * 只在骨架的函数调用位替换（字符串字面量在骨架里是空白，内容不可能被误伤）；
     * 日期值经 LocalDate 严格校验（真实日历日期）后复用
     * {@link DateConvertUtils#convertDateStrToLong} 的 Asia/Shanghai 零点语义，
     * 与平台日期列（BIGINT 毫秒）完全同一套取值。收紧项：af_date 的参数
     * 必须恰是一个 'YYYY-MM-DD' 字符串字面量，其他形态（列引用、函数嵌套）直接拒。
     */
    private String replaceAfDateLiterals(String sql, Skeleton skeleton) {
        // 收紧校验：af_date( 后面（骨架上）必须直接是右括号＝括号内恰是一个被剥空的字符串字面量
        Matcher probe = AF_DATE_CALL.matcher(skeleton.text());
        while (probe.find()) {
            int afterParen = skipWhitespace(skeleton, probe.end());
            if (afterParen >= skeleton.length() || skeleton.charAt(afterParen) != ')') {
                throw new Rejection(GuardVerdict.reject(CODE_INVALID_DATE_LITERAL,
                        "af_date 只支持单个 'YYYY-MM-DD' 字符串字面量参数（不接受列引用或嵌套表达式）",
                        ""));
            }
        }
        Matcher matcher = AF_DATE_BLANK_LITERAL_CALL.matcher(skeleton.text());
        List<int[]> skeletonSpans = new ArrayList<>();
        while (matcher.find()) {
            skeletonSpans.add(new int[]{matcher.start(), matcher.end()});
        }
        // 从右往左替换，避免前面的替换挪动后面的原文坐标
        for (int i = skeletonSpans.size() - 1; i >= 0; i--) {
            int skeletonStart = skeletonSpans.get(i)[0];
            int skeletonEnd = skeletonSpans.get(i)[1];
            int originalStart = skeleton.originalIndex(skeletonStart);
            int originalEnd = skeleton.originalIndex(skeletonEnd - 1) + 1;
            String spanText = sql.substring(originalStart, originalEnd);
            int leftParen = spanText.indexOf('(');
            int rightParen = spanText.lastIndexOf(')');
            String content = spanText.substring(leftParen + 1, rightParen).trim();
            Matcher dateMatcher = QUOTED_ISO_DATE.matcher(content);
            if (!dateMatcher.matches()) {
                throw new Rejection(GuardVerdict.reject(CODE_INVALID_DATE_LITERAL,
                        "af_date 参数必须是 'YYYY-MM-DD' 字面量，收到：" + abbreviate(content),
                        details("literal", abbreviate(content))));
            }
            String iso = dateMatcher.group(1) + "-" + dateMatcher.group(2) + "-" + dateMatcher.group(3);
            String compact = dateMatcher.group(1) + dateMatcher.group(2) + dateMatcher.group(3);
            long millis;
            try {
                // LocalDate 严格解析：拦掉 2024-13-01、2024-02-30 这类不存在的日期
                LocalDate.parse(iso);
                millis = DateConvertUtils.convertDateStrToLong(compact, "yyyyMMdd");
            } catch (DateTimeParseException e) {
                throw new Rejection(GuardVerdict.reject(CODE_INVALID_DATE_LITERAL,
                        "af_date 参数不是真实存在的日期：" + iso, details("literal", iso)));
            }
            if (millis <= 0) {
                throw new Rejection(GuardVerdict.reject(CODE_INVALID_DATE_LITERAL,
                        "af_date 参数无法转换为毫秒时间戳：" + iso, details("literal", iso)));
            }
            sql = sql.substring(0, originalStart) + millis + sql.substring(originalEnd);
        }
        return sql;
    }

    // ==================== 第 6 条：外层 LIMIT/OFFSET 解析与改写 ====================

    /**
     * 解析括号深度为 0 的最末 LIMIT/OFFSET（即作用于整个最外层查询的那条；
     * CTE 体与子查询内的 LIMIT 深度大于 0，不算外层）。无外层 LIMIT 拒 MISSING_LIMIT
     * 并引导看 listAssetTables 的写法示例；LIMIT 超硬上限改写为硬上限并标记 limitRewritten；
     * OFFSET 原样保留。非数字形态的 LIMIT（如 LIMIT ALL、LIMIT(5)）视同无外层 LIMIT 处理。
     *
     * <p>LIMIT 只接受纯数字（CR 必改 2）：数字后跟算术运算符或左括号（{@code LIMIT 2*200001}、
     * {@code LIMIT 999999999+1}）一律拒——PG 会按表达式求值，守卫按首个数字解析会导致
     * 「超上限改写」失效且 enforced_limit 错报；内层（CTE/子查询）LIMIT 同样适用。</p>
     */
    private GuardVerdict resolveOuterLimit(String sql, Skeleton skeleton) {
        int[] depthAt = parenDepths(skeleton);
        long limitValue = -1;
        int numberSkeletonStart = -1;
        int numberSkeletonEnd = -1;
        Matcher limitMatcher = LIMIT_CLAUSE.matcher(skeleton.text());
        while (limitMatcher.find()) {
            int afterNumber = skipWhitespace(skeleton, limitMatcher.end(1));
            if (afterNumber < skeleton.length()) {
                char next = skeleton.charAt(afterNumber);
                if (next == '+' || next == '-' || next == '*' || next == '/'
                        || next == '%' || next == '(' || next == '.') {
                    throw new Rejection(GuardVerdict.reject(CODE_MISSING_LIMIT,
                            "LIMIT 只接受纯数字（不支持算术表达式或小数），请直接写一个不超过 "
                                    + maxRows + " 的整数",
                            details("max_rows", maxRows, "reason", "limit_expression")));
                }
            }
            if (depthAt[limitMatcher.start()] == 0) {
                numberSkeletonStart = limitMatcher.start(1);
                numberSkeletonEnd = limitMatcher.end(1);
                try {
                    limitValue = Long.parseLong(limitMatcher.group(1));
                } catch (NumberFormatException e) {
                    // 纯数字但超出 long——必然大于硬上限，按超限改写处理
                    limitValue = Long.MAX_VALUE;
                }
            }
        }
        // OFFSET（方案 D3）：原样保留，不做解析改写；分页建议写在说明文件，超长翻页由超时兜底
        if (limitValue < 0) {
            throw new Rejection(GuardVerdict.reject(CODE_MISSING_LIMIT,
                    "查询缺少外层 LIMIT，已拒绝执行（不做隐式默认值，避免误以为拿到全量）；"
                            + "请显式写 LIMIT（最大 " + maxRows + "），写法示例见 listAssetTables 工具说明",
                    details("max_rows", maxRows)));
        }
        if (limitValue > maxRows) {
            int originalStart = skeleton.originalIndex(numberSkeletonStart);
            int originalEnd = skeleton.originalIndex(numberSkeletonEnd - 1) + 1;
            String rewritten = sql.substring(0, originalStart) + maxRows + sql.substring(originalEnd);
            return GuardVerdict.accept(rewritten, maxRows, true);
        }
        return GuardVerdict.accept(sql, limitValue, false);
    }

    // ==================== 骨架与文本辅助 ====================

    /**
     * 剥掉字符串字面量（单引号，含 '' 转义）与双引号标识符、行注释（--）、块注释（/\*\*\/）
     * 后的代码骨架，同时保留「骨架下标 → 原文下标」的位置映射（af_date 替换与 LIMIT 改写
     * 都靠它在骨架命中位回写原文）。算法与 duckdb_query_runner._code_skeleton 一致，
     * 额外产出位置映射；被剥区域以一个空格占位。字符串与注释里的分号、关键字不会误伤。
     * 检出双引号标识符时置 {@code hasQuotedIdentifier} 标记（代码位才会检出——字符串字面量
     * 与注释先被各自分支消费），由 {@link #checkNoQuotedIdentifiers} 统一拒绝。
     */
    private static Skeleton buildSkeleton(String sql) {
        StringBuilder text = new StringBuilder();
        List<Integer> mapping = new ArrayList<>();
        boolean quotedIdentifier = false;
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'') {
                int start = i;
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            i += 2; // '' 转义的引号，仍属字面量内部
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                blank(start, text, mapping);
            } else if (c == '"') {
                int start = i;
                i++;
                while (i < n && sql.charAt(i) != '"') {
                    i++;
                }
                i++;
                quotedIdentifier = true;
                blank(start, text, mapping);
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int start = i;
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                blank(start, text, mapping);
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int start = i;
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                blank(start, text, mapping);
            } else {
                text.append(c);
                mapping.add(i);
                i++;
            }
        }
        int[] map = new int[mapping.size()];
        for (int j = 0; j < map.length; j++) {
            map[j] = mapping.get(j);
        }
        return new Skeleton(text.toString(), map, quotedIdentifier);
    }

    /** 被剥区域以一个空格占位，位置映射指向区域起点（供骨架命中位回写原文用）。 */
    private static void blank(int start, StringBuilder text, List<Integer> mapping) {
        text.append(' ');
        mapping.add(start);
    }

    /** 每个骨架下标处「尚未闭合的括号数」（进入括号前深度）；用于识别最外层（深度 0）的 LIMIT/OFFSET。 */
    private static int[] parenDepths(Skeleton skeleton) {
        int[] depthAt = new int[skeleton.length()];
        int depth = 0;
        for (int i = 0; i < skeleton.length(); i++) {
            char c = skeleton.charAt(i);
            depthAt[i] = depth;
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            }
        }
        return depthAt;
    }

    private static boolean isIdentifierStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static int skipWhitespace(Skeleton skeleton, int pos) {
        while (pos < skeleton.length() && Character.isWhitespace(skeleton.charAt(pos))) {
            pos++;
        }
        return pos;
    }

    private static int skipWhitespaceBackward(Skeleton skeleton, int pos) {
        int i = pos - 1;
        while (i >= 0 && Character.isWhitespace(skeleton.charAt(i))) {
            i--;
        }
        return i;
    }

    /** pos 处须是标识符开头；返回 [起, 止) 区间。 */
    private static int[] readIdentifier(Skeleton skeleton, int pos) {
        Matcher matcher = IDENTIFIER.matcher(skeleton.text());
        matcher.region(pos, skeleton.length());
        if (!matcher.lookingAt()) {
            return new int[]{pos, pos};
        }
        return new int[]{pos, matcher.end()};
    }

    /** 跳过 pos 处的平衡括号体（pos 须指向 '('），返回闭合括号后的下标；不闭合则到末尾。 */
    private static int skipBalancedParentheses(Skeleton skeleton, int pos) {
        int depth = 0;
        int i = pos;
        while (i < skeleton.length()) {
            char c = skeleton.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
            i++;
        }
        return skeleton.length();
    }

    /**
     * FROM/JOIN 关系位的括号体（骨架内容区间 [open+1, closeEnd-1)，闭开区间）剥掉两侧空白
     * 与冗余外层括号后若恰是单个裸标识符，返回其小写名（{@code FROM (alphafrog_user) u}、
     * {@code FROM ((alphafrog_stock_daily)) t} 等形态）；子查询、表达式等多内容形态返回 null。
     */
    private static String bareIdentifierInParens(Skeleton skeleton, int open, int closeEnd) {
        int lo = open + 1;
        int hi = closeEnd - 1; // 内容区间 [lo, hi)，闭合括号本身在 closeEnd-1=hi 处（不含入内容）
        if (hi <= lo || closeEnd > skeleton.length() || skeleton.charAt(closeEnd - 1) != ')') {
            return null; // 不闭合或空的括号体：按复杂形态跳过，交给后续检查与 PG
        }
        while (true) {
            while (lo < hi && Character.isWhitespace(skeleton.charAt(lo))) {
                lo++;
            }
            while (lo < hi && Character.isWhitespace(skeleton.charAt(hi - 1))) {
                hi--;
            }
            if (lo >= hi) {
                return null;
            }
            if (skeleton.charAt(lo) == '(' && skeleton.charAt(hi - 1) == ')'
                    && parenClosesAt(skeleton, lo, hi - 1)) {
                lo++;
                hi--;
                continue;
            }
            break;
        }
        if (!isIdentifierStart(skeleton.charAt(lo))) {
            return null;
        }
        Matcher matcher = IDENTIFIER.matcher(skeleton.text());
        matcher.region(lo, hi);
        if (!matcher.matches()) {
            return null; // 含标识符以外内容（子查询、表达式、逗号列表等）
        }
        return matcher.group().toLowerCase(Locale.ROOT);
    }

    /** open 处的 '(' 是否恰在 close 处闭合（区间内深度归零一次且就在 close）。 */
    private static boolean parenClosesAt(Skeleton skeleton, int open, int close) {
        int depth = 0;
        for (int i = open; i <= close && i < skeleton.length(); i++) {
            char c = skeleton.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i == close;
                }
            }
        }
        return false;
    }

    /** pos 处是否是指定单词（大小写不敏感且后有词边界）。 */
    private static boolean matchesWord(Skeleton skeleton, int pos, String word) {
        if (pos < 0 || pos + word.length() > skeleton.length()) {
            return false;
        }
        if (!skeleton.text().regionMatches(true, pos, word, 0, word.length())) {
            return false;
        }
        int after = pos + word.length();
        return after >= skeleton.length() || !isIdentifierPart(skeleton.charAt(after));
    }

    private static boolean isIdentifierPart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '$';
    }

    /**
     * 关系/括号体后跳过可选别名（「AS 别名」或裸标识符两种形态）。子句/连接关键字
     * （WHERE/GROUP/ORDER/JOIN 等）不是别名，遇之即停在原地——停错位置最多漏掉一次
     * 逗号列表续读，不会漏核关系（FROM/JOIN 全文扫描各自命中）。
     */
    private static int skipAlias(Skeleton skeleton, int pos) {
        pos = skipWhitespace(skeleton, pos);
        if (pos >= skeleton.length() || !isIdentifierStart(skeleton.charAt(pos))) {
            return pos;
        }
        int[] identRange = readIdentifier(skeleton, pos);
        String token = skeleton.text().substring(identRange[0], identRange[1]).toLowerCase(Locale.ROOT);
        if (token.equals("as")) {
            // 「AS 别名」形态：再跳一个标识符
            int after = skipWhitespace(skeleton, identRange[1]);
            if (after < skeleton.length() && isIdentifierStart(skeleton.charAt(after))) {
                return readIdentifier(skeleton, after)[1];
            }
            return after;
        }
        if (CLAUSE_KEYWORDS.contains(token)) {
            return pos;
        }
        // 裸标识符别名
        return identRange[1];
    }

    private static String abbreviate(String value) {
        return value.length() <= 64 ? value : value.substring(0, 64) + "…";
    }

    /**
     * 手工拼一个扁平 JSON 对象（键为固定英文字面量，值做字符串转义），供错误结构
     * details_json 使用——守卫不引入 JSON 库依赖，转义规则覆盖引号/反斜杠/控制字符。
     */
    static String details(Object... keyValues) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(keyValues[i]).append("\":");
            Object value = keyValues[i + 1];
            if (value instanceof Number || value instanceof Boolean) {
                sb.append(value);
            } else {
                sb.append('"').append(jsonEscape(String.valueOf(value))).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static String jsonEscape(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    // ==================== 内部载体 ====================

    /** 骨架文本＋骨架下标到原文下标的位置映射（被剥区域映射到区域起点）
     * ＋是否检出双引号标识符（检出即拒，见 {@link #checkNoQuotedIdentifiers}）。 */
    private record Skeleton(String text, int[] map, boolean hasQuotedIdentifier) {

        int length() {
            return text.length();
        }

        char charAt(int index) {
            return text.charAt(index);
        }

        int originalIndex(int skeletonIndex) {
            return map[skeletonIndex];
        }
    }

    /** 守卫判定：放行（可执行 SQL＋强制后 LIMIT＋是否改写）或拒绝（稳定错误码＋中文说明＋机读详情）。 */
    public static final class GuardVerdict {

        private final boolean accepted;
        private final String executableSql;
        private final long enforcedLimit;
        private final boolean limitRewritten;
        private final String errorCode;
        private final String errorMessage;
        private final String errorDetailsJson;

        private GuardVerdict(boolean accepted, String executableSql, long enforcedLimit, boolean limitRewritten,
                             String errorCode, String errorMessage, String errorDetailsJson) {
            this.accepted = accepted;
            this.executableSql = executableSql;
            this.enforcedLimit = enforcedLimit;
            this.limitRewritten = limitRewritten;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.errorDetailsJson = errorDetailsJson;
        }

        static GuardVerdict accept(String executableSql, long enforcedLimit, boolean limitRewritten) {
            return new GuardVerdict(true, executableSql, enforcedLimit, limitRewritten, null, null, null);
        }

        static GuardVerdict reject(String errorCode, String errorMessage, String errorDetailsJson) {
            return new GuardVerdict(false, null, 0, false, errorCode, errorMessage,
                    errorDetailsJson == null ? "" : errorDetailsJson);
        }

        public boolean isAccepted() {
            return accepted;
        }

        public String getExecutableSql() {
            return executableSql;
        }

        public long getEnforcedLimit() {
            return enforcedLimit;
        }

        public boolean isLimitRewritten() {
            return limitRewritten;
        }

        public String getErrorCode() {
            return errorCode;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getErrorDetailsJson() {
            return errorDetailsJson;
        }
    }

    /** 内部控制流：任一步拒绝即抛出，由 {@link #inspect} 统一捕获为返回值。 */
    private static final class Rejection extends RuntimeException {

        private final GuardVerdict verdict;

        Rejection(GuardVerdict verdict) {
            super(verdict.getErrorMessage(), null, false, false);
            this.verdict = verdict;
        }

        GuardVerdict verdict() {
            return verdict;
        }
    }
}
