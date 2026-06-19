use std::collections::{BTreeMap, VecDeque};

use crate::order::{Order, Side};

#[derive(Debug, Default)]
pub struct OrderBook {
    bids: BTreeMap<i64, VecDeque<Order>>,
    asks: BTreeMap<i64, VecDeque<Order>>,
}

impl OrderBook {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn insert(&mut self, order: Order) {
        let map = self.side_map_mut(order.side);
        map.entry(order.price).or_default().push_back(order);
    }

    pub fn peek_best(&self, side: Side) -> Option<(i64, &Order)> {
        let map = self.side_map(side);
        let (&price, queue) = self.best_entry(map, side)?;
        queue.front().map(|order| (price, order))
    }

    pub fn poll_best(&mut self, side: Side) -> Option<Order> {
        let map = self.side_map_mut(side);
        let &price = match side {
            Side::Buy => map.last_key_value()?.0,
            Side::Sell => map.first_key_value()?.0,
        };

        let level = map.get_mut(&price).expect("price level exists");
        let order = level.pop_front();
        if level.is_empty() {
            map.remove(&price);
        }
        order
    }

    pub fn push_front(&mut self, order: Order) {
        let map = self.side_map_mut(order.side);
        map.entry(order.price).or_default().push_front(order);
    }

    fn best_entry<'a>(
        &self,
        map: &'a BTreeMap<i64, VecDeque<Order>>,
        side: Side,
    ) -> Option<(&'a i64, &'a VecDeque<Order>)> {
        match side {
            Side::Buy => map.last_key_value(),
            Side::Sell => map.first_key_value(),
        }
    }

    fn side_map(&self, side: Side) -> &BTreeMap<i64, VecDeque<Order>> {
        match side {
            Side::Buy => &self.bids,
            Side::Sell => &self.asks,
        }
    }

    fn side_map_mut(&mut self, side: Side) -> &mut BTreeMap<i64, VecDeque<Order>> {
        match side {
            Side::Buy => &mut self.bids,
            Side::Sell => &mut self.asks,
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
    fn 주문_삽입_후_매수는_최고가_매도는_최저가를_조회한다() {
        let mut book = OrderBook::new();
        book.insert(order(1, Side::Buy, 100, 5));
        book.insert(order(2, Side::Buy, 105, 5));
        book.insert(order(3, Side::Sell, 110, 5));
        book.insert(order(4, Side::Sell, 108, 5));

        let (price, best) = book.peek_best(Side::Buy).unwrap();
        assert_eq!(price, 105);
        assert_eq!(best.id, 2);

        let (price, best) = book.peek_best(Side::Sell).unwrap();
        assert_eq!(price, 108);
        assert_eq!(best.id, 4);
    }

    #[test]
    fn 같은_가격에서는_먼저_들어온_주문을_우선_조회한다() {
        let mut book = OrderBook::new();
        book.insert(order(1, Side::Buy, 100, 5));
        book.insert(order(2, Side::Buy, 100, 5));

        let (_, best) = book.peek_best(Side::Buy).unwrap();
        assert_eq!(best.id, 1);
    }

    #[test]
    fn 최우선_주문을_꺼내면_해당_주문이_제거된다() {
        let mut book = OrderBook::new();
        book.insert(order(1, Side::Sell, 100, 5));
        book.insert(order(2, Side::Sell, 100, 5));

        let polled = book.poll_best(Side::Sell).unwrap();
        assert_eq!(polled.id, 1);

        let (_, best) = book.peek_best(Side::Sell).unwrap();
        assert_eq!(best.id, 2);
    }

    #[test]
    fn 가격대의_마지막_주문을_꺼내면_가격대도_제거된다() {
        let mut book = OrderBook::new();
        book.insert(order(1, Side::Sell, 100, 5));

        book.poll_best(Side::Sell);

        assert!(book.peek_best(Side::Sell).is_none());
    }

    #[test]
    fn 비어있는_호가에서_주문을_꺼내면_none을_반환한다() {
        let mut book = OrderBook::new();

        assert!(book.poll_best(Side::Buy).is_none());
    }

    #[test]
    fn 부분체결_주문을_앞에_되돌리면_기존_우선순위가_유지된다() {
        let mut book = OrderBook::new();
        book.insert(order(1, Side::Sell, 100, 5));
        book.insert(order(2, Side::Sell, 100, 5));

        let mut polled = book.poll_best(Side::Sell).unwrap();
        polled.remaining = 2;
        book.push_front(polled);

        let (_, best) = book.peek_best(Side::Sell).unwrap();
        assert_eq!(best.id, 1);
        assert_eq!(best.remaining, 2);
    }
}
