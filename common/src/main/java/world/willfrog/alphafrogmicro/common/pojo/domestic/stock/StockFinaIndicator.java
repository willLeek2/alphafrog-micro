package world.willfrog.alphafrogmicro.common.pojo.domestic.stock;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_stock_fina_indicator",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code", "end_date"})
        })
public class StockFinaIndicator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "ts_code", nullable = false)
    String tsCode;

    @Column(name = "ann_date")
    Long annDate;

    @Column(name = "end_date", nullable = false)
    Long endDate;

    /** 盈利与回报类 JSONB（roe/roa/roic 系列、利润率、扣非），读取侧为 JSON 字符串。 */
    @Column(name = "profitability")
    String profitability;

    /** 每股类 JSONB（ps 系列、eps、bps）。 */
    @Column(name = "per_share")
    String perShare;

    /** 营运偿债现金流与资本结构类 JSONB。 */
    @Column(name = "capital_cash")
    String capitalCash;

    /** 增长类 JSONB（yoy、qoq、研发）。 */
    @Column(name = "growth")
    String growth;

    /** 未进四类的字段与接口以后新增的字段。 */
    @Column(name = "extended")
    String extended;
}
