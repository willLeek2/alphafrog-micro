package world.willfrog.agent.tools.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.tools.finance.FinanceResultModelProjector.FinanceDeclaredEvidence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 覆盖 {@link FinanceResultModelProjector} 的投影安全规则。
 */
class FinanceResultModelProjectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FinanceMethodSpecCatalog catalog = new FinanceMethodSpecCatalog(objectMapper);
    private final FinanceResultModelProjector projector = new FinanceResultModelProjector(catalog);






























}
