package world.willfrog.externalinfo.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.alphafrogmicro.externalinfo.idl.GetTodayMarketNewsRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalInfoDubboServiceImplTest {

    @Mock
    private MarketNewsService marketNewsService;

    @Mock
    private world.willfrog.externalinfo.retrieval.RagSearchServiceImpl ragSearchService;

    @Mock
    private world.willfrog.externalinfo.search.WebSearchOrchestrator webSearchOrchestrator;

}
