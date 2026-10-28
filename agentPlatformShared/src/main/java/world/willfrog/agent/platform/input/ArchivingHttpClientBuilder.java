package world.willfrog.agent.platform.input;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import java.time.Duration;
import java.util.Objects;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputScope;

/** 在SDK最终HTTP请求发送之前同步保存实际JSON，覆盖同步与流式入口。 */
public final class ArchivingHttpClientBuilder implements HttpClientBuilder {
    private final HttpClientBuilder delegate;

    public ArchivingHttpClientBuilder(HttpClientBuilder delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Duration connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration timeout) {
        delegate.connectTimeout(timeout);
        return this;
    }

    @Override
    public Duration readTimeout() {
        return delegate.readTimeout();
    }

    @Override
    public HttpClientBuilder readTimeout(Duration timeout) {
        delegate.readTimeout(timeout);
        return this;
    }

    @Override
    public HttpClient build() {
        HttpClient client = delegate.build();
        return new HttpClient() {
            @Override
            public SuccessfulHttpResponse execute(HttpRequest request) {
                beforeSend(request);
                return client.execute(request);
            }

            @Override
            public void execute(HttpRequest request, ServerSentEventParser parser,
                                ServerSentEventListener listener) {
                beforeSend(request);
                client.execute(request, parser, listener);
            }
        };
    }

    private static void beforeSend(HttpRequest request) {
        try {
            // 请求头及端点凭据不进入输入记录。委托方继续发送同一request对象。
            ModelInputScope.currentRequired().beforeSend(request.body());
        } catch (RuntimeException failure) {
            throw BlockedModelSendException.from(failure);
        }
    }
}
