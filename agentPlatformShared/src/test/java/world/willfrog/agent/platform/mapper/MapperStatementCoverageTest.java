package world.willfrog.agent.platform.mapper;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核对每个数据访问接口的方法在映射文件里都有同名语句。
 *
 * <p>MyBatis 对「接口声明了方法、XML 没写语句」只在调用那一刻报绑定异常，编译与现有测试都发现不了；
 * 这条测试在构建阶段就把这种缺口拦下来。</p>
 */
class MapperStatementCoverageTest {

    private static final List<Class<?>> MAPPERS = List.of(
            AsyncToolTaskMapper.class,
            RunCancellationMapper.class,
            WaitGroupMapper.class);

    @Test
    void everyMapperMethodHasStatement() throws Exception {
        Set<String> statementIds = statementIds();
        for (Class<?> mapper : MAPPERS) {
            for (Method method : mapper.getDeclaredMethods()) {
                assertTrue(statementIds.contains(method.getName()),
                        () -> mapper.getSimpleName() + "." + method.getName()
                                + " 在映射文件里没有同名语句");
            }
        }
    }

    private Set<String> statementIds() throws Exception {
        Set<String> ids = new HashSet<>();
        Path mapperDir = mapperDirectory();
        try (Stream<Path> files = Files.list(mapperDir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
                Document document = DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder().parse(file.toFile());
                collect(document, ids);
            }
        }
        return ids;
    }

    private void collect(Document document, Set<String> ids) {
        for (String tag : List.of("select", "insert", "update", "delete")) {
            NodeList nodes = document.getElementsByTagName(tag);
            for (int index = 0; index < nodes.getLength(); index++) {
                Element element = (Element) nodes.item(index);
                String id = element.getAttribute("id");
                if (!id.isBlank() && element.getParentNode().getNodeName().equals("mapper")) {
                    ids.add(id);
                }
            }
        }
    }

    private Path mapperDirectory() throws Exception {
        URL resource = MapperStatementCoverageTest.class.getClassLoader()
                .getResource("mapper/AsyncToolTaskMapper.xml");
        assertTrue(resource != null, "找不到映射文件目录");
        return Path.of(resource.toURI()).getParent();
    }
}
