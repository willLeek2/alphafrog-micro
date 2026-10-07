package world.willfrog.alphafrogmicro.common.pojo.domestic.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_stk_ah",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code", "trade_date"})
        })
public class StkAh {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    Long stkAhId;

    @Column(name = "ts_code", nullable = false)
    String tsCode;

    @Column(name = "hk_code")
    String hkCode;

    @Column(name = "trade_date", nullable = false)
    Long tradeDate;

    @Column(name = "close")
    Double close;

    @Column(name = "hk_close")
    Double hkClose;

    @Column(name = "pct_chg")
    Double pctChg;

    @Column(name = "hk_pct_chg")
    Double hkPctChg;

    @Column(name = "ah_comparison")
    Double ahComparison;

    @Column(name = "ah_premium")
    Double ahPremium;
}
