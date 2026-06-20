package dev.junyoung;

/**
 * 주문 방향. rust-engine의 {@code Side} enum과 동일하다.
 */
public enum Side {
    BUY,
    SELL;

    public Side opposite() {
        return switch (this) {
            case BUY -> SELL;
            case SELL -> BUY;
        };
    }
}
