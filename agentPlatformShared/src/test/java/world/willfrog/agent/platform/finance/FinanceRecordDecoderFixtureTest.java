package world.willfrog.agent.platform.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceRecordDecoderFixtureTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FinanceRecordDecoder decoder = new FinanceRecordDecoder(objectMapper);
    private final FinanceRecordSchemaValidator validator = new FinanceRecordSchemaValidator();




}
