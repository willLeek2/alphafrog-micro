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
    public DebugAssetSampleResponse randomIndexNamesByAmount(
            @RequestParam("start_year") int startYear,
            @RequestParam("end_year") int endYear,
            @RequestParam(value = "min_avg_amount", required = false) Double minAverageAmount,
            @RequestParam(value = "count", defaultValue = "1") int count) {
        return queryService.randomIndexNamesByCoverage(
                startYear, endYear, minAverageAmount, count);
    }

    @GetMapping("/stocks/random")
    public DebugAssetSampleResponse randomListedStocks(
            @RequestParam("start_year") int startYear,
            @RequestParam("end_year") int endYear,
            @RequestParam(value = "min_avg_amount", required = false) Double minAverageAmount,
            @RequestParam(value = "count", defaultValue = "1") int count) {
        return queryService.randomListedStocks(
                startYear, endYear, minAverageAmount, count);
    }

    @GetMapping("/etfs/random")
    public DebugAssetSampleResponse randomListedEtfs(
            @RequestParam("start_year") int startYear,
            @RequestParam("end_year") int endYear,
            @RequestParam(value = "min_avg_amount", required = false) Double minAverageAmount,
            @RequestParam(value = "count", defaultValue = "1") int count) {
        return queryService.randomListedEtfs(
                startYear, endYear, minAverageAmount, count);
    }
}
