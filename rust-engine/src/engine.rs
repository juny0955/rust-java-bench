use crate::book::OrderBook;
use crate::order::{Order, Side, Trade};

#[derive(Debug, Default)]
pub struct MatchingEngine {
    book: OrderBook,
    next_trade_seq: u64,
}

impl MatchingEngine {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn submit_limit_order(&mut self, mut taker: Order) -> Vec<Trade> {
        let mut trades = Vec::new();
        let opposite_side = taker.side.opposite();

        while taker.remaining > 0 {
            let Some((best_price, maker)) = self.book.peek_best_mut(opposite_side) else {
                break;
            };

            if !Self::crosses(taker.side, taker.price, best_price) {
                break;
            }

            let fill_qty = taker.remaining.min(maker.remaining);
            taker.fill(fill_qty);
            maker.fill(fill_qty);
            let maker_id = maker.id;
            let maker_filled = maker.remaining == 0;

            self.next_trade_seq += 1;
            trades.push(Trade {
                maker_order_id: maker_id,
                taker_order_id: taker.id,
                price: best_price,
                quantity: fill_qty,
                sequence: self.next_trade_seq,
            });

            if maker_filled {
                self.book.poll_best(opposite_side);
            }
        }

        if taker.remaining > 0 {
            self.book.insert(taker);
        }

        trades
    }

    fn crosses(side: Side, taker_price: i64, maker_price: i64) -> bool {
        match side {
            Side::Buy => taker_price >= maker_price,
            Side::Sell => taker_price <= maker_price,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn order(id: u64, side: Side, price: i64, quantity: u64) -> Order {
        Order {
            id,
            side,
            price,
            quantity,
            remaining: quantity,
            sequence: id,
        }
    }

    #[test]
    fn 체결되지_않는_주문은_거래없이_호가창에_대기한다() {
        let mut engine = MatchingEngine::new();

        let trades = engine.submit_limit_order(order(1, Side::Buy, 100, 10));
        assert!(trades.is_empty());

        let trades = engine.submit_limit_order(order(2, Side::Sell, 105, 10));
        assert!(trades.is_empty());
    }

    #[test]
    fn 교차_주문은_단일_대기_주문과_전량_체결된다() {
        let mut engine = MatchingEngine::new();
        engine.submit_limit_order(order(1, Side::Sell, 100, 10));

        let trades = engine.submit_limit_order(order(2, Side::Buy, 100, 10));

        assert_eq!(trades.len(), 1);
        let trade = &trades[0];
        assert_eq!(trade.maker_order_id, 1);
        assert_eq!(trade.taker_order_id, 2);
        assert_eq!(trade.price, 100);
        assert_eq!(trade.quantity, 10);
        assert_eq!(trade.sequence, 1);
    }

    #[test]
    fn 교차_주문은_부분_체결_후_남은_수량이_호가창에_대기한다() {
        let mut engine = MatchingEngine::new();
        engine.submit_limit_order(order(1, Side::Sell, 100, 5));

        let trades = engine.submit_limit_order(order(2, Side::Buy, 100, 10));

        assert_eq!(trades.len(), 1);
        assert_eq!(trades[0].quantity, 5);

        let trades = engine.submit_limit_order(order(3, Side::Sell, 100, 5));
        assert_eq!(trades.len(), 1);
        assert_eq!(trades[0].maker_order_id, 2);
        assert_eq!(trades[0].taker_order_id, 3);
        assert_eq!(trades[0].quantity, 5);
    }

    #[test]
    fn 주문은_여러_가격대와_여러_대기_주문을_순서대로_체결한다() {
        let mut engine = MatchingEngine::new();
        engine.submit_limit_order(order(1, Side::Sell, 100, 5));
        engine.submit_limit_order(order(2, Side::Sell, 100, 5));
        engine.submit_limit_order(order(3, Side::Sell, 101, 10));

        let trades = engine.submit_limit_order(order(4, Side::Buy, 101, 20));

        assert_eq!(trades.len(), 3);
        assert_eq!(trades[0].maker_order_id, 1);
        assert_eq!(trades[0].price, 100);
        assert_eq!(trades[0].quantity, 5);
        assert_eq!(trades[1].maker_order_id, 2);
        assert_eq!(trades[1].price, 100);
        assert_eq!(trades[1].quantity, 5);
        assert_eq!(trades[2].maker_order_id, 3);
        assert_eq!(trades[2].price, 101);
        assert_eq!(trades[2].quantity, 10);

        let sequences: Vec<u64> = trades.iter().map(|t| t.sequence).collect();
        assert_eq!(sequences, vec![1, 2, 3]);
    }

    #[test]
    fn 전량_체결된_가격대는_호가창에서_제거된다() {
        let mut engine = MatchingEngine::new();
        engine.submit_limit_order(order(1, Side::Sell, 100, 5));
        engine.submit_limit_order(order(2, Side::Buy, 100, 5));

        let trades = engine.submit_limit_order(order(3, Side::Sell, 100, 3));
        assert!(trades.is_empty());

        let trades = engine.submit_limit_order(order(4, Side::Buy, 100, 3));
        assert_eq!(trades.len(), 1);
        assert!(engine.book.peek_best(Side::Sell).is_none());
    }
}
