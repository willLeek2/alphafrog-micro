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
