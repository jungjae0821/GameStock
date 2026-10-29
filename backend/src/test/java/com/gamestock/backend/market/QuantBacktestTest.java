package com.gamestock.backend.market;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuantBacktestTest {
    @Test void fillsAtNextOpenAndNeverOnSignalClose() {
        var p=QuantCompiler.compile("SNSCRIPT","buy when close < 15 quantity 2",null).program();
        var result=QuantBacktest.run(p,QuantCompilerTest.bars(10,20,30),100,0,0);
        assertEquals(1,result.trades().size());assertEquals(20,result.trades().get(0).price());
        assertEquals(120,result.finalEquity());assertEquals(0,result.equity().get(0).quantity());
    }
    @Test void cannotShortOrSpendMoreThanCashAndFirstRuleWins() {
        var p=QuantCompiler.compile("SNSCRIPT","sell when true quantity 1\nbuy when true quantity 1",null).program();
        var result=QuantBacktest.run(p,QuantCompilerTest.bars(10,20,30),100,0,0);
        assertEquals(0,result.trades().size());assertEquals(2,result.rejectedOrders());
        var buy=QuantCompiler.compile("SNSCRIPT","buy when true quantity 10",null).program();
        assertEquals(2,QuantBacktest.run(buy,QuantCompilerTest.bars(10,20,30),10,0,0).rejectedOrders());
    }
    @Test void chargesFeesAndSlippageAndTracksDrawdown() {
        var p=QuantCompiler.compile("SNSCRIPT","buy when true quantity 1",null).program();
        var result=QuantBacktest.run(p,QuantCompilerTest.bars(100,100,50),1000,10,100);
        assertEquals(101,result.trades().get(0).price());assertEquals(1,result.trades().get(0).fee());
        assertEquals(946.5,result.finalEquity());assertTrue(result.maxDrawdownPercent()>5);
    }
}
