package world.willfrog.externalinfo.retrieval;

import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.alphafrogmicro.externalinfo.idl.RagSearchRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagSearchServiceImplTest {

    @Mock
    private QdrantClient qdrantClient;

    @Mock
    private EmbeddingApiClient embeddingApiClient;

    @Test
    void ragSearchShouldMapChunkIndexFromPayload() throws Exception {
        when(embeddingApiClient.embed("现金流")).thenReturn(List.of(0.1f, 0.2f));
        when(qdrantClient.searchAsync(any(SearchPoints.class))).thenReturn(Futures.immediateFuture(List.of(
                scoredPoint(Map.of(
                        "title", stringValue("年报"),
                        "text", stringValue("经营现金流内容"),
                        "oss_url", stringValue("oss://bucket/doc.md"),
                        "chunk_index", intValue(12)
                ))
        )));

        var response = service().ragSearch(RagSearchRequest.newBuilder()
                .setQueryText("现金流")
                .setTopK(3)
                .build());

        assertEquals(1, response.getItemsCount());
        assertEquals("年报", response.getItems(0).getTitle());
        assertEquals("oss://bucket/doc.md", response.getItems(0).getOssUrl());
        assertEquals(12, response.getItems(0).getChunkIndex());

        ArgumentCaptor<SearchPoints> searchCaptor = ArgumentCaptor.forClass(SearchPoints.class);
        verify(qdrantClient).searchAsync(searchCaptor.capture());
        assertEquals(3, searchCaptor.getValue().getLimit());
    }

    @Test
    void ragSearchShouldFallbackMissingOrInvalidChunkIndexToZero() throws Exception {
        when(embeddingApiClient.embed("估值")).thenReturn(List.of(0.3f));
        when(qdrantClient.searchAsync(any(SearchPoints.class))).thenReturn(Futures.immediateFuture(List.of(
                scoredPoint(Map.of("title", stringValue("missing"))),
                scoredPoint(Map.of(
                        "title", stringValue("invalid"),
                        "chunk_index", stringValue("not-a-number")
                ))
        )));

        var response = service().ragSearch(RagSearchRequest.newBuilder()
                .setQueryText("估值")
                .build());

        assertEquals(2, response.getItemsCount());
        assertEquals(0, response.getItems(0).getChunkIndex());
        assertEquals(0, response.getItems(1).getChunkIndex());
    }

    private static ScoredPoint scoredPoint(Map<String, Value> payload) {
        return ScoredPoint.newBuilder()
                .setScore(0.86f)
                .putAllPayload(payload)
                .build();
    }

    private RagSearchServiceImpl service() {
        RagSearchServiceImpl service = new RagSearchServiceImpl(qdrantClient, embeddingApiClient);
        ReflectionTestUtils.setField(service, "collectionName", "test_collection");
        return service;
    }

    private static Value stringValue(String value) {
        return Value.newBuilder().setStringValue(value).build();
    }

    private static Value intValue(long value) {
        return Value.newBuilder().setIntegerValue(value).build();
    }
}
