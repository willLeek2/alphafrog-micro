package world.willfrog.agent.platform.mapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 把一份迁移脚本切成一条条可执行语句。
 *
 * <p>给真库用例用：脚本是给迁移管理器整份执行的，单条执行要先切句。切句规则只看三件事——
 * 单引号里的分号不算分界、{@code --} 注释整行丢掉、空语句丢掉。脚本里没有函数体与美元引用，
 * 所以不需要更复杂的规则；真出现那种写法时应当改用迁移管理器，而不是在这里补解析器。</p>
 *
 * <p>脚本文件按文件名在整个仓库里找，找不到就报错：真库用例读不到脚本时应该失败，不该假装通过。</p>
 */
public final class MigrationStatements {

    private MigrationStatements() {
    }

    /** 按文件名找脚本；同名只应当存在一份。 */
    public static Path locate(String fileName) {
        Path upgrades = findUpgradesDir();
        try (Stream<Path> stream = Files.walk(upgrades, 2)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(fileName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("迁移目录里没有脚本：" + fileName));
        } catch (IOException e) {
            throw new IllegalStateException("遍历迁移目录失败：" + upgrades, e);
        }
    }

    /** 读脚本正文。 */
    public static String read(String fileName) {
        Path path = locate(fileName);
        return read(path);
    }

    /** 读某一份脚本正文。 */
    public static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("读取迁移脚本失败：" + path, e);
        }
    }

    /**
     * 真实的升级链：先按文件名顺序排 {@code migrate/migrations/init} 下的建表脚本，
     * 再按「版本目录、文件名」顺序排升级脚本，直到指定的那一份（含）为止。
     *
     * <p>真库用例用它把库升到目标脚本之前的状态，再单独应用目标脚本：这样验证的是真实升级路径，
     * 不是一份手写的简化前置表。</p>
     */
    public static List<Path> upgradeChainUpTo(String lastFileName) {
        List<Path> chain = new ArrayList<>();
        chain.addAll(initScripts());
        for (Path path : MigrationScripts.pathsInOrder()) {
            chain.add(path);
            if (path.getFileName().toString().equals(lastFileName)) {
                return chain;
            }
        }
        throw new IllegalStateException("升级链里没有找到收尾脚本：" + lastFileName);
    }

    /** {@code migrate/migrations/init} 下的脚本，按文件名升序。 */
    public static List<Path> initScripts() {
        Path init = findUpgradesDir().getParent().resolve("init");
        if (!Files.isDirectory(init)) {
            throw new IllegalStateException("没有找到初始化脚本目录：" + init);
        }
        try (Stream<Path> stream = Files.list(init)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("列初始化脚本失败：" + init, e);
        }
    }

    /** 切成语句，保持脚本里的先后顺序。 */
    public static List<String> split(String script) {
        if (script == null) {
            throw new IllegalArgumentException("脚本正文不能为空");
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        int index = 0;
        while (index < script.length()) {
            char ch = script.charAt(index);
            if (!inQuote && ch == '-' && index + 1 < script.length() && script.charAt(index + 1) == '-') {
                while (index < script.length() && script.charAt(index) != '\n') {
                    index++;
                }
                current.append('\n');
                continue;
            }
            if (ch == '\'') {
                // 两个连着单引号是转义写法，里面的引号不算收尾。
                if (inQuote && index + 1 < script.length() && script.charAt(index + 1) == '\'') {
                    current.append("''");
                    index += 2;
                    continue;
                }
                inQuote = !inQuote;
                current.append(ch);
                index++;
                continue;
            }
            if (ch == ';' && !inQuote) {
                addIfUseful(statements, current);
                current.setLength(0);
                index++;
                continue;
            }
            current.append(ch);
            index++;
        }
        if (inQuote) {
            throw new IllegalStateException("脚本里的单引号没有收尾，无法切句");
        }
        addIfUseful(statements, current);
        return statements;
    }

    private static void addIfUseful(List<String> statements, StringBuilder current) {
        String statement = current.toString().strip();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
    }

    private static Path findUpgradesDir() {
        Path userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path base = userDir; base != null; base = base.getParent()) {
            Path candidate = base.resolve("migrate/migrations/upgrades");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("在 " + userDir + " 的各级父目录下都没有 migrate/migrations/upgrades");
    }
}
