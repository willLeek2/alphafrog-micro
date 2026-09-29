package world.willfrog.alphafrogmicro.common.dao.domestic.stock;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.CbDaily;

@Mapper
public interface CbDailyDao {

    @Insert({
            "INSERT INTO alphafrog_cb_daily (ts_code, trade_date, pre_close, open, high, low, close, change," +
                    " pct_chg, vol, amount, premium) " +
                    "VALUES (#{tsCode}, #{tradeDate}, #{preClose}, #{open}, #{high}, #{low}, #{close}, #{change}," +
                    " #{pctChg}, #{vol}, #{amount}, #{premium}::jsonb)" +
                    "ON CONFLICT(ts_code, trade_date) DO NOTHING"
    })
    int insertCbDaily(CbDaily cbDaily);
}
