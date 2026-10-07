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
import world.willfrog.alphafrogmicro.common.pojo.domestic.quote.Quote;


@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "alphafrog_cb_daily",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"ts_code", "trade_date"})
        })
public class CbDaily extends Quote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    Long cbDailyId;

    @Column(name = "premium", columnDefinition = "JSONB")
    String premium;
}
