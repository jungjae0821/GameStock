package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QuantCompilerTest {
    static List<MarketModels.ChartCandle> bars(long... prices) {
        List<MarketModels.ChartCandle> result=new ArrayList<>();
        for(int i=0;i<prices.length;i++)result.add(new MarketModels.ChartCandle("2026-01-01T00:"+String.format("%02d",i)+":00Z",prices[i],prices[i]+2,prices[i]-2,prices[i],10,"2026-01-01T00:00:00Z"));
        return result;
    }
    @Test void compilesScriptAndBlocksToIdenticalSignals() throws Exception {
        var script=QuantCompiler.compile("SNSCRIPT","let fast = ta.sma(close, 2)\nplot average = fast\nbuy when close > fast quantity 2",null);
        var blocks=new ObjectMapper().readTree("""
            {"version":1,"bindings":[{"name":"fast","expression":{"op":"sma","period":2,"args":[{"op":"ref","name":"close"}]}}],
             "plots":[{"name":"average","expression":{"op":"ref","name":"fast"}}],
             "rules":[{"side":"BUY","quantity":2,"condition":{"op":">","args":[{"op":"ref","name":"close"},{"op":"ref","name":"fast"}]}}]}
            """);
        var block=QuantCompiler.compile("BLOCKS",null,blocks);
        assertTrue(script.valid(),script.diagnostics().toString());assertTrue(block.valid());assertEquals(script.program(),block.program());
        var evaluation=QuantCompiler.evaluate(script.program(),bars(10,12,9,14));
        assertNull(evaluation.plots().get("average").get(0));
        assertArrayEquals(new double[]{Double.NaN,1,0,1},evaluation.signals().get(0));
    }
    @Test void rejectsUnsafeUnsupportedAndUnboundedPrograms() {
        for(String source:List.of("let x = java.lang.Runtime()","let x = missing + 1","let x = ta.sma(close, 1)",
            "buy when true quantity 1001","let close = 5","let x = ta.sma(close, 2.5)","while true", "let x = close[1]",
            "let x = "+"(".repeat(30)+"1"+")".repeat(30),"EOF ignored", "let x =")) {
            assertFalse(QuantCompiler.compile("SNSCRIPT",source,null).valid(),source);
        }
        assertFalse(QuantCompiler.compile("SNSCRIPT","x".repeat(32769),null).valid());
    }
    @Test void reportsSyntaxLocation() {
        var result=QuantCompiler.compile("SNSCRIPT","// comment\nlet x = @",null);
        assertFalse(result.valid());assertEquals(2,result.diagnostics().get(0).line());assertEquals(9,result.diagnostics().get(0).column());
    }
    @Test void crossoverIsCausalAndDoesNotTriggerOnWarmupOrDivisionByZero() {
        var p=QuantCompiler.compile("SNSCRIPT","plot ratio = close / 0\nbuy when ta.crossover(close, 10) quantity 1",null).program();
        var result=QuantCompiler.evaluate(p,bars(9,10,11,12,9,11));
        assertArrayEquals(new double[]{0,0,1,0,0,1},result.signals().get(0));
        assertTrue(result.plots().get("ratio").stream().allMatch(Objects::isNull));
        assertEquals(result.plots().get("ratio").subList(0,3),QuantCompiler.evaluate(p,bars(9,10,11)).plots().get("ratio"));
    }
    @Test void operatorPrecedenceAndNoFutureData() {
        var p=QuantCompiler.compile("SNSCRIPT","plot x = 2 + 3 * 4\nplot e = ta.ema(close, 2)\nbuy when not false and close >= 10 quantity 1",null).program();
        var full=QuantCompiler.evaluate(p,bars(10,12,14,1,100));var prefix=QuantCompiler.evaluate(p,bars(10,12,14));
        assertEquals(List.of(14.0,14.0,14.0),prefix.plots().get("x"));
        assertEquals(prefix.plots().get("e"),full.plots().get("e").subList(0,3));
    }
    @Test void malformedBlocksHaveDiagnosticsRatherThanServerErrors() throws Exception {
        ObjectMapper mapper=new ObjectMapper();
        for(String source:List.of("{}","{\"version\":1.5}","{\"version\":1,\"bindings\":[],\"plots\":[],\"rules\":[{\"side\":\"BUY\",\"quantity\":1.5}]}"))
            assertFalse(QuantCompiler.compile("BLOCKS",null,mapper.readTree(source)).valid());
    }
}
