package world.willfrog.agent.tools.dataanalysis;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 在 SQL 送到主库之前检查语句：只留一条 SELECT，表必须在四个资产类别的名单里，
 * 列必须属于语句里的那张表，并把最外层 LIMIT 改成不超过当前档位的行数。
 */
public final class MarketDbSqlGuard {

    public static final Set<String> ALLOWED_TABLES = allowedTables();

    private static Set<String> allowedTables() {
        Set<String> names = new LinkedHashSet<>();
        for (String category : MarketDbCatalog.CATEGORIES) {
            names.addAll(MarketDbCatalog.tables(category));
        }
        return Set.copyOf(names);
    }

    private static final Set<String> FORBIDDEN_FUNCTIONS = Set.of(
            "pg_read_file", "pg_read_binary_file", "pg_ls_dir", "pg_stat_file",
            "lo_import", "lo_export", "lo_get", "dblink", "dblink_exec",
            "pg_sleep", "set_config", "pg_terminate_backend", "pg_cancel_backend");

    public record Prepared(String sql, boolean capped, int appliedLimit) {}

    private MarketDbSqlGuard() {
    }

    public static Prepared prepare(String sql, int rowCap) {
        if (rowCap <= 0) {
            throw new MarketSqlRejected("行数上限无效");
        }
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException parseFailure) {
            throw new MarketSqlRejected("SQL 无法解析，只接受一条 SELECT");
        }
        List<Statement> parsed = statements == null ? List.of() : statements.getStatements();
        if (parsed == null || parsed.size() != 1 || !(parsed.get(0) instanceof Select select)) {
            throw new MarketSqlRejected("只接受一条 SELECT");
        }
        if (select.getForClause() != null) {
            throw new MarketSqlRejected("不接受锁定行的 SELECT");
        }
        List<String> cteNames = new ArrayList<>();
        collectCteNames(select, cteNames);
        for (String cteName : cteNames) {
            if (ALLOWED_TABLES.contains(cteName)) {
                throw new MarketSqlRejected("公共表表达式不能占用允许表的名字");
            }
        }
        List<String> tableNames;
        try {
            tableNames = new TablesNamesFinder().getTableList((Statement) select);
        } catch (RuntimeException readFailure) {
            throw new MarketSqlRejected("无法读取语句中的表名");
        }
        Set<String> referenced = new LinkedHashSet<>();
        for (String rawName : tableNames) {
            String normalized = normalizeTable(rawName);
            if (cteNames.contains(normalized)) {
                continue;
            }
            if (!ALLOWED_TABLES.contains(normalized)) {
                throw new MarketSqlRejected("表 " + rawName
                        + " 不在允许范围内。请先按 ETF、股票、指数、基金查看表名");
            }
            referenced.add(normalized);
        }
        if (referenced.isEmpty()) {
            throw new MarketSqlRejected("语句没有引用允许的行情表。请先按 ETF、股票、指数、基金查看表名");
        }
        ColumnCheck check = new ColumnCheck();
        walk(select, check);
        int applied = rewrite(select.getLimit(), rowCap, select::setLimit, check);
        return new Prepared(select.toString(), check.capped, applied);
    }

    private static void collectCteNames(Select select, List<String> names) {
        if (select == null || select.getWithItemsList() == null) {
            return;
        }
        for (WithItem item : select.getWithItemsList()) {
            Alias alias = item.getAlias();
            if (alias != null && alias.getName() != null) {
                names.add(alias.getName().replace("\"", "").toLowerCase(Locale.ROOT));
            }
            if (item.getSelect() != null) {
                collectCteNames(item.getSelect(), names);
            }
        }
    }

    static String normalizeTable(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new MarketSqlRejected("表名为空");
        }
        String name = rawName.replace("\"", "").trim().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String schema = name.substring(0, dot);
            if (!"public".equals(schema)) {
                throw new MarketSqlRejected("只接受 public 或不写 schema 的表名：" + rawName);
            }
            name = name.substring(dot + 1);
        }
        return name;
    }

    private static void walk(Select select, ColumnCheck check) {
        if (select == null) {
            return;
        }
        if (select.getFetch() != null) {
            throw new MarketSqlRejected("不接受 FETCH，请改用 LIMIT");
        }
        if (select.getWithItemsList() != null) {
            for (WithItem item : select.getWithItemsList()) {
                walk(item.getSelect(), check);
            }
        }
        if (select instanceof PlainSelect plain) {
            walkPlain(plain, check);
            return;
        }
        if (select instanceof SetOperationList setOperation) {
            if (setOperation.getSelects() != null) {
                for (Select part : setOperation.getSelects()) {
                    walk(part, check);
                }
            }
            acceptOrderBy(select.getOrderByElements(), check);
            return;
        }
        if (select instanceof ParenthesedSelect parenthesed) {
            walk(parenthesed.getSelect(), check);
            return;
        }
        throw new MarketSqlRejected("只接受单条 SELECT");
    }

    private static void walkPlain(PlainSelect plain, ColumnCheck check) {
        if (plain.getForMode() != null) {
            throw new MarketSqlRejected("不接受锁定行的 SELECT");
        }
        if (plain.getIntoTables() != null && !plain.getIntoTables().isEmpty()) {
            throw new MarketSqlRejected("不接受 SELECT INTO");
        }
        check.pushAliasScope();
        check.pushTableScope(tableScope(plain));
        try {
        if (plain.getSelectItems() != null) {
            for (SelectItem<?> item : plain.getSelectItems()) {
                Expression expression = item.getExpression();
                if (expression instanceof AllColumns || expression instanceof AllTableColumns) {
                    throw new MarketSqlRejected("不接受 SELECT *。请写出列名，列必须属于语句里的那张表");
                }
                if (item.getAlias() != null && item.getAlias().getName() != null) {
                    check.addAlias(item.getAlias().getName().replace("\"", "").toLowerCase(Locale.ROOT));
                }
            }
            for (SelectItem<?> item : plain.getSelectItems()) {
                accept(item.getExpression(), check);
            }
        }
        accept(plain.getWhere(), check);
        accept(plain.getHaving(), check);
        if (plain.getGroupBy() != null) {
            GroupByElement groupBy = plain.getGroupBy();
            ExpressionList<?> expressions = groupBy.getGroupByExpressionList();
            if (expressions != null && expressions.getExpressions() != null) {
                for (Expression expression : expressions.getExpressions()) {
                    accept(expression, check);
                }
            }
        }
        acceptOrderBy(plain.getOrderByElements(), check);
        walkFrom(plain.getFromItem(), check);
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                walkFrom(join.getRightItem(), check);
                accept(join.getOnExpression(), check);
                if (join.getUsingColumns() != null) {
                    for (Column column : join.getUsingColumns()) {
                        column.accept(check);
                    }
                }
            }
        }
        } finally {
            check.popTableScope();
            check.popAliasScope();
        }
    }

    private static Map<String, String> tableScope(PlainSelect plain) {
        Map<String, String> scope = new LinkedHashMap<>();
        addTable(plain.getFromItem(), scope);
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                addTable(join.getRightItem(), scope);
            }
        }
        return scope;
    }

    private static void addTable(FromItem item, Map<String, String> scope) {
        if (!(item instanceof Table table)) {
            return;
        }
        String raw = table.getSchemaName() == null || table.getSchemaName().isBlank()
                ? table.getName()
                : table.getSchemaName() + "." + table.getName();
        String canonical = normalizeTable(raw);
        scope.put(canonical, canonical);
        if (table.getAlias() != null && table.getAlias().getName() != null) {
            String alias = table.getAlias().getName().replace("\"", "").toLowerCase(Locale.ROOT);
            scope.put(alias, canonical);
        }
    }

    private static void acceptOrderBy(List<OrderByElement> orderByElements, ColumnCheck check) {
        if (orderByElements == null) {
            return;
        }
        for (OrderByElement orderBy : orderByElements) {
            accept(orderBy.getExpression(), check);
        }
    }

    private static void walkFrom(FromItem fromItem, ColumnCheck check) {
        if (fromItem instanceof ParenthesedSelect parenthesed) {
            walk(parenthesed.getSelect(), check);
        }
    }

    private static void accept(Expression expression, ColumnCheck check) {
        if (expression != null) {
            expression.accept(check);
        }
    }

    private static int rewrite(Limit existing, int rowCap, Consumer<Limit> setter, ColumnCheck check) {
        Long modelLimit = null;
        if (existing != null && existing.getRowCount() instanceof LongValue longValue) {
            modelLimit = longValue.getValue();
        } else if (existing != null && existing.getRowCount() != null) {
            modelLimit = Long.MAX_VALUE;
        }
        if (modelLimit != null && modelLimit <= 0) {
            throw new MarketSqlRejected("LIMIT 必须是正整数");
        }
        int applied = rowCap;
        if (modelLimit != null && modelLimit <= rowCap) {
            applied = modelLimit.intValue();
            check.capped = false;
        } else {
            check.capped = true;
        }
        Limit limit = existing == null ? new Limit() : existing;
        limit.setRowCount(new LongValue(String.valueOf(applied)));
        setter.accept(limit);
        return applied;
    }

    private static final class ColumnCheck extends ExpressionVisitorAdapter {

        private boolean capped;
        private final Deque<Set<String>> aliasScopes = new ArrayDeque<>();
        private final Deque<Map<String, String>> tableScopes = new ArrayDeque<>();

        private void pushAliasScope() {
            aliasScopes.push(new LinkedHashSet<>());
        }

        private void popAliasScope() {
            aliasScopes.pop();
        }

        private void pushTableScope(Map<String, String> scope) {
            tableScopes.push(scope);
        }

        private void popTableScope() {
            tableScopes.pop();
        }

        private void addAlias(String name) {
            aliasScopes.peek().add(name);
        }

        @Override
        public void visit(Select select) {
            walk(select, this);
        }

        @Override
        public void visit(Column column) {
            String name = column.getColumnName() == null
                    ? ""
                    : column.getColumnName().replace("\"", "").toLowerCase(Locale.ROOT);
            boolean qualified = column.getTable() != null && column.getTable().getName() != null
                    && !column.getTable().getName().isBlank();
            if (!qualified && !aliasScopes.isEmpty() && aliasScopes.peek().contains(name)) {
                return;
            }
            Map<String, String> scope = tableScopes.isEmpty() ? Map.of() : tableScopes.peek();
            if (!qualified) {
                boolean found = false;
                for (String tableName : new LinkedHashSet<>(scope.values())) {
                    if (MarketDbCatalog.columns(tableName).contains(name)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new MarketSqlRejected("列 " + column.getColumnName() + " 不属于本次查询里的表");
                }
                return;
            }
            String qualifier = column.getTable().getName().replace("\"", "").toLowerCase(Locale.ROOT);
            String tableName = scope.get(qualifier);
            if (tableName == null) {
                throw new MarketSqlRejected("列前缀 " + qualifier + " 对不上本次查询里的表");
            }
            if (!MarketDbCatalog.columns(tableName).contains(name)) {
                throw new MarketSqlRejected("列 " + name + " 不属于表 " + tableName
                        + "。该表的列：" + String.join("、", MarketDbCatalog.columns(tableName)));
            }
        }

        @Override
        public void visit(Function function) {
            String name = functionBaseName(function.getName());
            if (FORBIDDEN_FUNCTIONS.contains(name)) {
                throw new MarketSqlRejected("函数 " + function.getName() + " 不在允许范围内");
            }
            super.visit(function);
        }
    }

    /** 带 schema 的函数名只取最后一段，避免 pg_catalog.pg_sleep 绕过禁用名单。 */
    static String functionBaseName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "";
        }
        String name = rawName.replace("\"", "").trim();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            name = name.substring(dot + 1);
        }
        return name.toLowerCase(Locale.ROOT);
    }
}
