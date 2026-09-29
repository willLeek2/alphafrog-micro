package world.willfrog.alphafrogmicro.common.dao.domestic.stock;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StkAh;

@Mapper
public interface StkAhDao {

    @Insert({
            "INSERT INTO alphafrog_stk_ah (ts_code, hk_code, trade_date, close, hk_close, pct_chg, hk_pct_chg," +
                    " ah_comparison, ah_premium) " +
                    "VALUES (#{tsCode}, #{hkCode}, #{tradeDate}, #{close}, #{hkClose}, #{pctChg}, #{hkPctChg}," +
                    " #{ahComparison}, #{ahPremium})" +
                    "ON CONFLICT(ts_code, trade_date) DO NOTHING"
    })
    int insertStkAh(StkAh stkAh);
}
