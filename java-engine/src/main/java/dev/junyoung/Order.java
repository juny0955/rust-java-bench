package dev.junyoung;

/**
 * 주문. rust-engine의 {@code Order} struct와 동일하다.
 *
 * <p>{@code price}는 부호 있는 정수(i64), 나머지 수량/식별자는 비음수(u64)를 long으로 표현한다.
 * {@code remaining}만 체결 진행에 따라 가변이며, {@code quantity}는 원래 수량으로 고정된다.
 */
public final class Order {
    public final long id;
    public final Side side;
    public final long price;
    public final long quantity;
    public long remaining;
    public final long sequence;

    public Order(long id, Side side, long price, long quantity, long remaining, long sequence) {
        this.id = id;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.remaining = remaining;
        this.sequence = sequence;
    }

    /**
     * 체결 수량만큼 잔량을 감소시킨다. rust-engine의 {@code Order::fill}과 동일하게
     * 경계 검사를 하지 않는다(호출측이 {@code min(taker, maker)}로 보장).
     */
    public void fill(long qty) {
        this.remaining -= qty;
    }
}
