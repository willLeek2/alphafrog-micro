package world.willfrog.agentlangchain.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注入点上写的名字，必须真的有一个同名的 Bean。
 *
 * <p>这类错在单测里全绿：装配一个带 {@code @Qualifier} 的构造器要起整个 Spring 上下文，而绝大多数
 * 用例只用替身，所以名字写错一个字母，一路到服务启动才报出来——真到了那时候，日志里只有一句
 * 「找不到符合条件的 Bean」，还得自己去翻是哪一处。这里直接按源码核对：每一处
 * {@code @Qualifier("X")} 都要找得到一处 {@code @Bean(name = "X")}。</p>
 *
 * <p>注入点引用的是别的模块或框架提供的同名 Bean 时，这条核查本来就不适用；到那时把它加进
 * {@link #EXTERNAL_BEAN_NAMES} 并在旁边写明是谁提供的。</p>
 */
class LangchainBeanNameWiringTest {

    /** 由别的模块或框架提供、不在本模块源码里的 Bean 名字。 */
    private static final Set<String> EXTERNAL_BEAN_NAMES = Set.of();

    private static final Pattern BEAN_NAME =
            Pattern.compile("@Bean\\s*\\(\\s*name\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern QUALIFIER =
            Pattern.compile("@Qualifier\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");

}
