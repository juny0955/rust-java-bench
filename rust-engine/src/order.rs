pub type OrderId = u64;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Side {
    Buy,
    Sell,
}

impl Side {
    pub fn opposite(self) -> Side {
        match self {
            Side::Buy => Side::Sell,
            Side::Sell => Side::Buy,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Order {
    pub id: OrderId,
    pub side: Side,
    pub price: i64,
    pub quantity: u64,
    pub remaining: u64,
    pub sequence: u64,
}

impl Order {
    pub fn fill(&mut self, qty: u64) {
        self.remaining -= qty;
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Trade {
    pub maker_order_id: OrderId,
    pub taker_order_id: OrderId,
    pub price: i64,
    pub quantity: u64,
    pub sequence: u64,
}
