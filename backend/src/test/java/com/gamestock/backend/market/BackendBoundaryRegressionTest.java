package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BackendBoundaryRegressionTest {
    @Test void checkpointResumesTheValueAndRandomPathWithADifferentProcessSeed() {
        var stock=new BatchOrderBook.Stock(1,"GI",10000,10000,10000,10000,true);
        var original=new MarketConsensusValue(17);
        original.observe(stock,11000,.1,null,1000);
        original.observe(stock,11000,.1,null,2000);
        var restarted=new MarketConsensusValue(999);
        restarted.restore(original.checkpoint());
        for(long now=3000;now<20000;now+=1000)
            assertEquals(original.observe(stock,10500,.05,null,now),restarted.observe(stock,10500,.05,null,now),0);
    }
    @Test void chunkedResponseCancelsBeforeAccumulatingAnOversizedChunk() {
        var body=BoundedHttpBody.subscriber(4);
        var subscription=mock(Flow.Subscription.class);
        body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2,3})));
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{4,5})));
        verify(subscription).cancel();
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }
    @Test void responseExactlyAtTheLimitIsAccepted() {
        var body=BoundedHttpBody.subscriber(4);
        body.onSubscribe(mock(Flow.Subscription.class));
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2}),ByteBuffer.wrap(new byte[]{3,4})));
        body.onComplete();
        assertArrayEquals(new byte[]{1,2,3,4},body.getBody().toCompletableFuture().join());
    }
    @Test void publisherAddressGuardRejectsPrivateAndTransitionRangesWithoutNetworkRequests() throws Exception {
        for(String address:List.of("fd00::1","fc00::1","::1","fe80::1","64:ff9b::a00:1","2002:a00:1::1","2001:db8::1","127.0.0.1","10.0.0.1","100.64.1.1","169.254.169.254"))
            assertFalse(PublicNetworkAddress.allowed(InetAddress.getByName(address)),address);
        for(String address:List.of("8.8.8.8","2606:4700:4700::1111"))
            assertTrue(PublicNetworkAddress.allowed(InetAddress.getByName(address)),address);
    }
    @Test void quantitiesRejectFractionalNullAndStringValuesBeforeAnOrderIsCreated() {
        var mapper=new ObjectMapper();
        for(String quantity:List.of("1.9","null","\"1\""))
            assertThrows(Exception.class,()->mapper.readValue("{\"quantity\":"+quantity+"}",DerivativeModels.CloseRequest.class));
        assertDoesNotThrow(()->mapper.readValue("{\"quantity\":1}",DerivativeModels.CloseRequest.class));
        assertThrows(Exception.class,()->mapper.readValue("{\"quantity\":1.9}",MarketModels.OrderRequest.class));
        assertThrows(Exception.class,()->mapper.readValue("{\"quantity\":1,\"price\":10000.9}",MarketModels.OrderRequest.class));
        assertThrows(Exception.class,()->mapper.readValue("{\"amount\":10000.9}",LoanService.LoanRequest.class));
        assertThrows(Exception.class,()->mapper.readValue("{\"quantity\":1,\"leverage\":5.9}",DerivativeModels.OrderRequest.class));
        assertDoesNotThrow(()->mapper.readValue("{\"quantity\":1,\"leverage\":null}",DerivativeModels.OrderRequest.class));
    }
    @Test void OppositeParticipantFlowsDoNotBecomeOppositeCausalClaimsWhenOnlyPriceSignChanges() {
        var market=new MarketService(null,null,null,null);
        String up=MarketChartQueries.priceDriversReason(1.0,0.0,10L,-10L,0L);
        String down=MarketChartQueries.priceDriversReason(-1.0,0.0,10L,-10L,0L);
        assertEquals(up,down);
    }
}
