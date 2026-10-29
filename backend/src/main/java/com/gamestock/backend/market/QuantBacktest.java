package com.gamestock.backend.market;

import java.util.*;
import static com.gamestock.backend.market.MarketModels.ChartCandle;

/** Educational long-only simulation: signal at close, fill at the next available bar open. */
final class QuantBacktest {
    record Fill(String signalAt,String filledAt,String side,int quantity,double price,double fee) {}
    record Equity(String time,double cash,int quantity,double equity) {}
    record Result(String fillModel,double initialCash,double finalEquity,double returnPercent,double maxDrawdownPercent,
                  int rejectedOrders,List<Fill> trades,List<Equity> equity,Map<String,List<Double>> plots) {}
    static Result run(QuantCompiler.Program program,List<ChartCandle> bars,long initialCash,int feeBps,int slippageBps) {
        if(initialCash<1 || initialCash>1_000_000_000_000L || feeBps<0 || feeBps>1000 || slippageBps<0 || slippageBps>1000)
            throw new IllegalArgumentException("초기자금은 1~10^12, 수수료/슬리피지는 0~1000 bps입니다.");
        var evaluation=QuantCompiler.evaluate(program,bars);
        double cash=initialCash,peak=initialCash,drawdown=0;int held=0,rejected=0;
        List<Fill> trades=new ArrayList<>();List<Equity> equity=new ArrayList<>();
        for(int i=0;i<bars.size();i++) {
            ChartCandle bar=bars.get(i);
            if(i>0) for(int r=0;r<program.rules().size();r++) {
                if(!QuantCompiler.truth(evaluation.signals().get(r)[i-1]))continue;
                var rule=program.rules().get(r);boolean buy=rule.side().equals("BUY");
                double price=bar.openPrice()*(1+(buy?1:-1)*slippageBps/10000.0);
                double gross=price*rule.quantity(),fee=feeBps==0?0:Math.max(1,Math.round(gross*feeBps/10000.0));
                if((buy && cash>=gross+fee) || (!buy && held>=rule.quantity())) {
                    cash+=buy?-gross-fee:gross-fee;held+=buy?rule.quantity():-rule.quantity();
                    trades.add(new Fill(bars.get(i-1).recordedAt(),bar.recordedAt(),rule.side(),rule.quantity(),price,fee));
                } else rejected++;
                break; // first matching rule wins, including a rejected order
            }
            double total=cash+held*(double)bar.closePrice();peak=Math.max(peak,total);
            drawdown=Math.max(drawdown,(peak-total)/peak*100);
            equity.add(new Equity(bar.recordedAt(),cash,held,total));
        }
        double total=equity.isEmpty()?initialCash:equity.get(equity.size()-1).equity();
        return new Result("NEXT_BAR_OPEN_LONG_ONLY",initialCash,total,(total/initialCash-1)*100,drawdown,rejected,
            List.copyOf(trades),List.copyOf(equity),evaluation.plots());
    }
}
