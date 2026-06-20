package dev.junyoung;

import java.util.ArrayList;
import java.util.List;

/**
 * 매칭 엔진. rust-engine의 {@code MatchingEngine}과 동일하다.
 *
 * <p>지정가 주문을 받아 반대편 호가창과 가격-시간 우선순위로 체결하고, 남은 잔량은
 * 호가창에 대기시킨다. 체결가는 항상 maker(대기 주문)의 가격이며, 거래 시퀀스는
 * 호출 간 누적되는 전역 카운터로 1부터 선증가한다.
 */
public final class MatchingEngine {
    final OrderBook book = new OrderBook();
    private long nextTradeSeq = 0;

    /** rust-engine의 {@code submit_limit_order}와 동일한 로직. */
    public List<Trade> submitLimitOrder(Order taker) {
        List<Trade> trades = new ArrayList<>();
        Side oppositeSide = taker.side.opposite();

        while (taker.remaining > 0) {
            Order maker = book.peekBest(oppositeSide);
            if (maker == null) {
                break;
            }
            if (!crosses(taker.side, taker.price, maker.price)) {
                break;
            }

            long fillQty = Math.min(taker.remaining, maker.remaining);
            taker.fill(fillQty);
            maker.fill(fillQty);
            long makerId = maker.id;
            long makerPrice = maker.price;
            boolean makerFilled = maker.remaining == 0;

            nextTradeSeq += 1;
            trades.add(new Trade(makerId, taker.id, makerPrice, fillQty, nextTradeSeq));

            if (makerFilled) {
                book.pollBest(oppositeSide);
            }
        }

        if (taker.remaining > 0) {
            book.insert(taker);
        }

        return trades;
    }

    private static boolean crosses(Side side, long takerPrice, long makerPrice) {
        return switch (side) {
            case BUY -> takerPrice >= makerPrice;
            case SELL -> takerPrice <= makerPrice;
        };
    }
}
