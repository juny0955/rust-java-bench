package dev.junyoung;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** rust-engine engine.rs의 단위 테스트를 포팅한다. */
class MatchingEngineTest {

    private static Order order(long id, Side side, long price, long quantity) {
        return new Order(id, side, price, quantity, quantity, id);
    }

    @Test
    @DisplayName("체결되지 않는 주문은 거래없이 호가창에 대기한다")
    void nonCrossingOrdersRestWithoutTrades() {
        MatchingEngine engine = new MatchingEngine();

        assertTrue(engine.submitLimitOrder(order(1, Side.BUY, 100, 10)).isEmpty());
        assertTrue(engine.submitLimitOrder(order(2, Side.SELL, 105, 10)).isEmpty());
    }

    @Test
    @DisplayName("교차 주문은 단일 대기 주문과 전량 체결된다")
    void crossingOrderFullyFillsSingleResting() {
        MatchingEngine engine = new MatchingEngine();
        engine.submitLimitOrder(order(1, Side.SELL, 100, 10));

        List<Trade> trades = engine.submitLimitOrder(order(2, Side.BUY, 100, 10));

        assertEquals(1, trades.size());
        Trade trade = trades.get(0);
        assertEquals(1, trade.makerOrderId());
        assertEquals(2, trade.takerOrderId());
        assertEquals(100, trade.price());
        assertEquals(10, trade.quantity());
        assertEquals(1, trade.sequence());
    }

    @Test
    @DisplayName("교차 주문은 부분 체결 후 남은 수량이 호가창에 대기한다")
    void crossingOrderPartiallyFillsThenRests() {
        MatchingEngine engine = new MatchingEngine();
        engine.submitLimitOrder(order(1, Side.SELL, 100, 5));

        List<Trade> trades = engine.submitLimitOrder(order(2, Side.BUY, 100, 10));
        assertEquals(1, trades.size());
        assertEquals(5, trades.get(0).quantity());

        List<Trade> next = engine.submitLimitOrder(order(3, Side.SELL, 100, 5));
        assertEquals(1, next.size());
        assertEquals(2, next.get(0).makerOrderId());
        assertEquals(3, next.get(0).takerOrderId());
        assertEquals(5, next.get(0).quantity());
    }

    @Test
    @DisplayName("주문은 여러 가격대와 여러 대기 주문을 순서대로 체결한다")
    void fillsMultiplePriceLevelsInOrder() {
        MatchingEngine engine = new MatchingEngine();
        engine.submitLimitOrder(order(1, Side.SELL, 100, 5));
        engine.submitLimitOrder(order(2, Side.SELL, 100, 5));
        engine.submitLimitOrder(order(3, Side.SELL, 101, 10));

        List<Trade> trades = engine.submitLimitOrder(order(4, Side.BUY, 101, 20));

        assertEquals(3, trades.size());
        assertEquals(1, trades.get(0).makerOrderId());
        assertEquals(100, trades.get(0).price());
        assertEquals(5, trades.get(0).quantity());
        assertEquals(2, trades.get(1).makerOrderId());
        assertEquals(100, trades.get(1).price());
        assertEquals(5, trades.get(1).quantity());
        assertEquals(3, trades.get(2).makerOrderId());
        assertEquals(101, trades.get(2).price());
        assertEquals(10, trades.get(2).quantity());

        List<Long> sequences = trades.stream().map(Trade::sequence).toList();
        assertEquals(List.of(1L, 2L, 3L), sequences);
    }

    @Test
    @DisplayName("전량 체결된 가격대는 호가창에서 제거된다")
    void fullyFilledPriceLevelIsRemoved() {
        MatchingEngine engine = new MatchingEngine();
        engine.submitLimitOrder(order(1, Side.SELL, 100, 5));
        engine.submitLimitOrder(order(2, Side.BUY, 100, 5));

        List<Trade> trades = engine.submitLimitOrder(order(3, Side.SELL, 100, 3));
        assertTrue(trades.isEmpty());

        List<Trade> next = engine.submitLimitOrder(order(4, Side.BUY, 100, 3));
        assertEquals(1, next.size());
        assertNull(engine.book.peekBest(Side.SELL));
    }
}
