package world.willfrog.alphafrogmicro.frontend.service.agent;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentTracePartsServiceTest {

    @Test
    void prepareClampsPartSizeAndPartsReassembleToOriginalPayload() throws Exception {
        byte[] raw = "safe full trace payload".repeat(10_000).getBytes(StandardCharsets.UTF_8);
        AgentRawTraceDetailMapper.FullTracePayload payload =
                new AgentRawTraceDetailMapper.FullTracePayload(Map.of(), raw, raw);
        AgentTracePartsService service = new AgentTracePartsService();

        AgentTracePartsService.PreparedFullParts prepared = service.prepare(payload, 1);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        for (int index = 0; index < prepared.totalParts(); index++) {
            compressed.write(service.part(prepared, index));
        }

        assertEquals(64 * 1024, prepared.partSize());
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed.toByteArray()))) {
            assertArrayEquals(raw, gzip.readAllBytes());
        }
    }
}
