package world.willfrog.alphafrogmicro.domestic.fetch.debug;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/debug")
public class DomesticDebugController {

    private final DomesticDebugQueryService queryService;

    public DomesticDebugController(DomesticDebugQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/index-constituents/random")
    public List<DebugAssetNameResponse> randomIndexConstituents(
            @RequestParam("ts_code") String tsCode,
            @RequestParam("year") int year,
            @RequestParam(value = "count", defaultValue = "1") int count) {
        return queryService.randomIndexConstituentStocks(tsCode, year, count);
    }

    @GetMapping("/sw-l3-industries/random")
    public List<String> randomSwL3Industries(
            @RequestParam(value = "count", defaultValue = "1") int count) {
        return queryService.randomSwL3IndustryNames(count);
    }

    @GetMapping("/index-names/random-by-amount")
    public List<DebugAssetNameResponse> randomIndexNamesByAmount(
            @RequestParam("start_date") String startDate,
            @RequestParam("end_date") String endDate,
            @RequestParam("min_amount") double minAmount,
            @RequestParam(value = "count", defaultValue = "1") int count) {
        return queryService.randomIndexNamesByAmountRange(startDate, endDate, minAmount, count);
    }

    @GetMapping("/stocks/random")
    public List<DebugAssetNameResponse> randomListedStocks(
            @RequestParam(value = "count", defaultValue = "1") int count) {
        return queryService.randomListedStocks(count);
    }

    @GetMapping("/etfs/random")
    public List<DebugAssetNameResponse> randomListedEtfs(
            @RequestParam(value = "count", defaultValue = "1") int count) {
        return queryService.randomListedEtfs(count);
    }
}
