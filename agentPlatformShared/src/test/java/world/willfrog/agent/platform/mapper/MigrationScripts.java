package world.willfrog.agent.platform.mapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 测试里读迁移脚本的共同工具。
 *
 * <p>约束会被后来的迁移重写（宽化取值、补列、加表），所以「某一份脚本里写了什么」这种检查必须能读到
 * <em>最后一份</em>定义它的脚本，否则宽化之后旧断言还在比对老取值。这里把「按版本与文件名排序取全部脚本」
 * 和「取某条约束的取值」两件事集中一份，避免每个测试各写一遍排序规则。</p>
 *
 * <p>找不到就报错，不跳过：脚本不在仓库里本身就说明合同漂了。</p>
 */
final class MigrationScripts {

    private MigrationScripts() {
    }

    /** upgrades 目录下的全部脚本，按 (版本目录, 文件名) 升序。 */
    static List<Path> pathsInOrder() {
        Path upgrades = findUpgradesDir();
        try (Stream<Path> stream = Files.walk(upgrades, 2)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted((left, right) -> {
                        int byVersion = compareVersionDir(
                                left.getParent().getFileName().toString(),
                                right.getParent().getFileName().toString());
                        return byVersion != 0 ? byVersion
                                : left.getFileName().toString().compareTo(right.getFileName().toString());
                    })
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("遍历迁移目录失败：" + upgrades, e);
        }
    }

    /** 全部脚本的正文，顺序同上。 */
    static List<String> allInOrder() {
        List<String> scripts = new ArrayList<>();
        for (Path path : pathsInOrder()) {
            scripts.add(read(path));
        }
        return scripts;
    }

    /** 最后一份包含这个标记的脚本正文。 */
    static String lastContaining(String marker) {
        List<Path> paths = pathsInOrder();
        for (int index = paths.size() - 1; index >= 0; index--) {
            String text = read(paths.get(index));
            if (text.contains(marker)) {
                return text;
            }
        }
        throw new IllegalStateException("没有任何迁移脚本包含：" + marker);
    }

    /**
     * 取某条约束里 {@code IN (...)} 列出的取值。
     *
     * <p>约束有两种写法：建表语句里的 {@code CONSTRAINT 名字 CHECK (...)}，以及后面迁移里
     * 「先 DROP IF EXISTS 再 ADD CONSTRAINT」的宽化写法。两种都要读得出来，所以按名字定位到
     * 最后一条<em>定义</em>（跳过 DROP 那条），再只取其中 {@code IN (} 里那一串取值——
     * 一条建表语句里会同时出现好几组取值，整段一起取会混进别人的值。</p>
     */
    static List<String> constraintValues(String script, String constraintName) {
        String marker = "CONSTRAINT " + constraintName;
        int position = -1;
        int searchFrom = 0;
        while (true) {
            int found = script.indexOf(marker, searchFrom);
            if (found < 0) {
                break;
            }
            int lineStart = script.lastIndexOf('\n', found) + 1;
            boolean dropped = script.substring(lineStart, found).contains("DROP");
            if (!dropped) {
                position = found;
            }
            searchFrom = found + marker.length();
        }
        if (position < 0) {
            throw new IllegalStateException("脚本里没有定义约束 " + constraintName);
        }
        // 只在这条语句内部找：跨到后面的语句会读到别人的取值清单，那是最难发现的假通过。
        int statementEnd = script.indexOf(';', position);
        String tail = script.substring(position, statementEnd < 0 ? script.length() : statementEnd);
        Matcher inList = Pattern.compile("IN\\s*\\(").matcher(tail);
        if (!inList.find()) {
            throw new IllegalStateException("约束 " + constraintName + " 不是取值清单，无法逐项比对");
        }
        String before = tail.substring(0, inList.start());
        if (before.split("CHECK", -1).length - 1 != 1) {
            throw new IllegalStateException(
                    "约束 " + constraintName + " 前面不止一条 CHECK，说明取到的是别人的取值清单");
        }
        int valueStart = inList.end();
        int valueEnd = tail.indexOf(')', valueStart);
        if (valueEnd < 0) {
            throw new IllegalStateException("约束 " + constraintName + " 的取值清单没有收尾的右括号");
        }
        return quotedValues(tail.substring(valueStart, valueEnd));
    }

    /** 正文里全部单引号大写标识，按出现顺序。 */
    static List<String> quotedValues(String text) {
        List<String> values = new ArrayList<>();
        Matcher matcher = Pattern.compile("'([A-Z0-9_]+)'").matcher(text);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("读取迁移脚本失败：" + path, e);
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

    /** 版本目录名比较：按数字段逐段比，避免 v1.10 排到 v1.5 前面。 */
    private static int compareVersionDir(String left, String right) {
        int[] leftParts = versionNumbers(left);
        int[] rightParts = versionNumbers(right);
        for (int index = 0; index < Math.max(leftParts.length, rightParts.length); index++) {
            int leftValue = index < leftParts.length ? leftParts[index] : 0;
            int rightValue = index < rightParts.length ? rightParts[index] : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return left.compareTo(right);
    }

    private static int[] versionNumbers(String versionDir) {
        Matcher matcher = Pattern.compile("(\\d+)").matcher(versionDir);
        List<Integer> numbers = new ArrayList<>();
        while (matcher.find()) {
            numbers.add(Integer.parseInt(matcher.group(1)));
        }
        int[] result = new int[numbers.size()];
        for (int index = 0; index < numbers.size(); index++) {
            result[index] = numbers.get(index);
        }
        return result;
    }
}
