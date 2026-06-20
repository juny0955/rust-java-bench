package dev.junyoung;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** rust-engine book.rs의 단위 테스트를 포팅한다. */
class OrderBookTest {

    private static Order order(long id, Side side, long price, long quantity) {
        return new Order(id, side, price, quantity, quantity, id);
    }

    @Test
    @DisplayName("주문 삽입 후 매수는 최고가, 매도는 최저가를 조회한다")
    void peeksBestBidHighestAndBestAskLowest() {
        OrderBook book = new OrderBook();
        book.insert(order(1, Side.BUY, 100, 5));
        book.insert(order(2, Side.BUY, 105, 5));
        book.insert(order(3, Side.SELL, 110, 5));
        book.insert(order(4, Side.SELL, 108, 5));

        Order bestBid = book.peekBest(Side.BUY);
        assertEquals(105, bestBid.price);
        assertEquals(2, bestBid.id);

        Order bestAsk = book.peekBest(Side.SELL);
        assertEquals(108, bestAsk.price);
        assertEquals(4, bestAsk.id);
    }

    @Test
    @DisplayName("같은 가격에서는 먼저 들어온 주문을 우선 조회한다")
    void peeksEarliestOrderAtSamePrice() {
        OrderBook book = new OrderBook();
        book.insert(order(1, Side.BUY, 100, 5));
        book.insert(order(2, Side.BUY, 100, 5));

        assertEquals(1, book.peekBest(Side.BUY).id);
    }

    @Test
    @DisplayName("최우선 주문을 꺼내면 해당 주문이 제거된다")
    void pollRemovesBestOrder() {
        OrderBook book = new OrderBook();
        book.insert(order(1, Side.SELL, 100, 5));
        book.insert(order(2, Side.SELL, 100, 5));

        Order polled = book.pollBest(Side.SELL);
        assertEquals(1, polled.id);

        assertEquals(2, book.peekBest(Side.SELL).id);
    }

    @Test
    @DisplayName("가격대의 마지막 주문을 꺼내면 가격대도 제거된다")
    void pollLastOrderRemovesPriceLevel() {
        OrderBook book = new OrderBook();
        book.insert(order(1, Side.SELL, 100, 5));

        book.pollBest(Side.SELL);

        assertNull(book.peekBest(Side.SELL));
    }

    @Test
    @DisplayName("비어있는 호가에서 주문을 꺼내면 null을 반환한다")
    void pollEmptySideReturnsNull() {
        OrderBook book = new OrderBook();

        assertNull(book.pollBest(Side.BUY));
    }

    @Test
    @DisplayName("peekBest로 체결하면 제거나 재삽입 없이 우선순위가 유지된다")
    void inPlaceFillKeepsPriority() {
        OrderBook book = new OrderBook();
        book.insert(order(1, Side.SELL, 100, 5));
        book.insert(order(2, Side.SELL, 100, 5));

        Order best = book.peekBest(Side.SELL);
        assertEquals(100, best.price);
        assertEquals(1, best.id);
        best.remaining -= 3;

        Order stillBest = book.peekBest(Side.SELL);
        assertEquals(1, stillBest.id);
        assertEquals(2, stillBest.remaining);
    }
}
