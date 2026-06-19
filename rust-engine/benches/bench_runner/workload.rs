use rust_engine::{Order, Side};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Scenario {
    ThinBook,
    ActiveFill,
    WorstCaseCross,
}

const BASE_PRICE: i64 = 100_000;
const SWEEP_LEVELS: u64 = 1_000;
const SWEEP_MAKER_QTY: u64 = 1;

#[derive(Debug, Clone, Copy)]
struct WorstCaseState {
    maker_side: Side,
}

pub struct WorkloadGenerator {
    scenario: Scenario,
    rng_state: u64,
    next_id: u64,
    total_emitted: u64,
    count: u64,
    cross_state: WorstCaseState,
}

impl WorkloadGenerator {
    pub fn new(scenario: Scenario, seed: u64, count: u64) -> Self {
        Self {
            scenario,
            rng_state: if seed == 0 {
                0x9E37_79B9_7F4A_7C15
            } else {
                seed
            },
            next_id: 1,
            total_emitted: 0,
            count,
            cross_state: WorstCaseState {
                maker_side: Side::Sell,
            },
        }
    }

    pub fn is_exhausted(&self) -> bool {
        self.total_emitted >= self.count
    }

    pub fn next_batch(&mut self, batch_size: usize) -> Vec<Order> {
        let remaining = self.count - self.total_emitted;
        let n = (batch_size as u64).min(remaining) as usize;
        let mut batch = Vec::with_capacity(n);
        for _ in 0..n {
            batch.push(self.next_order());
        }
        batch
    }

    fn next_rand(&mut self) -> u64 {
        let mut x = self.rng_state;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.rng_state = x;
        x
    }

    fn next_order(&mut self) -> Order {
        let order = match self.scenario {
            Scenario::ThinBook => self.next_thin_book_order(),
            Scenario::ActiveFill => self.next_active_fill_order(),
            Scenario::WorstCaseCross => self.next_worst_case_order(),
        };
        self.total_emitted += 1;
        order
    }

    fn alloc_id(&mut self) -> u64 {
        let id = self.next_id;
        self.next_id += 1;
        id
    }

    fn next_thin_book_order(&mut self) -> Order {
        let id = self.alloc_id();
        let side = if self.next_rand().is_multiple_of(2) {
            Side::Buy
        } else {
            Side::Sell
        };
        let offset = 1_000 + (self.next_rand() % 1_000) as i64;
        let price = match side {
            Side::Buy => BASE_PRICE - offset,
            Side::Sell => BASE_PRICE + offset,
        };
        let qty = 10 + self.next_rand() % 90;
        order(id, side, price, qty)
    }

    fn next_active_fill_order(&mut self) -> Order {
        let id = self.alloc_id();
        let side = if self.next_rand().is_multiple_of(2) {
            Side::Buy
        } else {
            Side::Sell
        };
        let jitter = (self.next_rand() % 5) as i64 - 2;
        let price = BASE_PRICE + jitter;
        let qty = 10 + self.next_rand() % 90;
        order(id, side, price, qty)
    }

    fn next_worst_case_order(&mut self) -> Order {
        let id = self.alloc_id();
        let cycle_pos = self.total_emitted % (SWEEP_LEVELS + 1);
        let maker_side = self.cross_state.maker_side;

        if cycle_pos < SWEEP_LEVELS {
            let level = cycle_pos as i64;
            let price = match maker_side {
                Side::Buy => BASE_PRICE - level,
                Side::Sell => BASE_PRICE + level,
            };
            return order(id, maker_side, price, SWEEP_MAKER_QTY);
        }

        self.cross_state.maker_side = maker_side.opposite();
        let taker_side = maker_side.opposite();
        let price = match taker_side {
            Side::Buy => BASE_PRICE + SWEEP_LEVELS as i64,
            Side::Sell => BASE_PRICE - SWEEP_LEVELS as i64,
        };
        order(id, taker_side, price, SWEEP_LEVELS * SWEEP_MAKER_QTY)
    }
}

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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn is_exhausted_after_exact_count() {
        let mut generator = WorkloadGenerator::new(Scenario::ThinBook, 42, 10);
        assert!(!generator.is_exhausted());
        let batch = generator.next_batch(7);
        assert_eq!(batch.len(), 7);
        assert!(!generator.is_exhausted());
        let batch = generator.next_batch(7);
        assert_eq!(batch.len(), 3);
        assert!(generator.is_exhausted());
    }

    fn collect_all(scenario: Scenario, seed: u64, count: u64, batch_size: usize) -> Vec<Order> {
        let mut generator = WorkloadGenerator::new(scenario, seed, count);
        let mut all = Vec::new();
        while !generator.is_exhausted() {
            all.extend(generator.next_batch(batch_size));
        }
        all
    }

    #[test]
    fn same_seed_generates_same_sequence_for_any_batch_size() {
        let small_batches = collect_all(Scenario::ThinBook, 123, 10_000, 1_000);
        let large_batches = collect_all(Scenario::ThinBook, 123, 10_000, 100_000);
        assert_eq!(small_batches, large_batches);

        let small_batches = collect_all(Scenario::WorstCaseCross, 7, 10_000, 1_000);
        let large_batches = collect_all(Scenario::WorstCaseCross, 7, 10_000, 100_000);
        assert_eq!(small_batches, large_batches);
    }

    #[test]
    fn worst_case_cross_sweeps_multiple_price_levels_with_one_taker() {
        use rust_engine::MatchingEngine;

        let mut generator = WorkloadGenerator::new(Scenario::WorstCaseCross, 99, 3_003);
        let mut engine = MatchingEngine::new();
        let mut max_trades_for_one_order = 0usize;
        let mut crossed_multiple_prices = false;

        while !generator.is_exhausted() {
            for order in generator.next_batch(128) {
                let trades = engine.submit_limit_order(order);
                max_trades_for_one_order = max_trades_for_one_order.max(trades.len());
                if trades.len() >= 2 {
                    let first_price = trades[0].price;
                    crossed_multiple_prices = trades.iter().any(|trade| trade.price != first_price);
                }
            }
        }

        assert!(
            max_trades_for_one_order >= SWEEP_LEVELS as usize,
            "expected a sweep taker to generate many trades, got {max_trades_for_one_order}"
        );
        assert!(crossed_multiple_prices);
    }
}
