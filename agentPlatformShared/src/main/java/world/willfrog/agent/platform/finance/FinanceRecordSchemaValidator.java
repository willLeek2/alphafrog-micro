package world.willfrog.agent.platform.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Validates v1 records and adds the finite-number check JSON Schema cannot express. */
@Component
public class FinanceRecordSchemaValidator {

    private static final String SCHEMA_PATH = "finance/records/metric-record-v1.schema.json";

    private final JsonSchema schema;

    public FinanceRecordSchemaValidator() {
        try (InputStream input = new ClassPathResource(SCHEMA_PATH).getInputStream()) {
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(input);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load " + SCHEMA_PATH, exception);
        }
    }

    public ValidationResult validate(FinanceRecordDecoder.DecodedRecord record) {
        List<String> errors = new ArrayList<>();
        if (record.decodeError() != null) {
            errors.add(record.decodeError());
            return new ValidationResult(false, errors);
        }
        JsonNode node = record.json();
        Set<ValidationMessage> messages = schema.validate(node);
        messages.stream()
                .map(ValidationMessage::getMessage)
                .sorted(Comparator.naturalOrder())
                .forEach(errors::add);

        JsonNode value = node.get("value");
        if (value != null && value.isNumber()) {
            try {
                BigDecimal decimal = value.decimalValue();
                if (decimal == null) {
                    errors.add("$.value must be finite");
                }
            } catch (ArithmeticException exception) {
                errors.add("$.value must be finite");
            }
        }
        return new ValidationResult(errors.isEmpty(), errors);
    }

    public record ValidationResult(boolean valid, List<String> errors) {
        public ValidationResult {
            errors = List.copyOf(errors);
        }
    }
}
