package dev.junyoung;

import it.unimi.dsi.fastutil.longs.Long2ObjectRBTreeMap;
import java.util.ArrayDeque;

/**
 * 호가창. rust-engine의 {@code OrderBook}과 동일하다.
 *
 * <p>양측을 {@code Long2ObjectRBTreeMap<ArrayDeque<Order>>}로 보관한다.
 * 가격 키는 오름차순 정렬되며(가격 우선), 동일 가격대 내에서는 {@code ArrayDeque}의
 * 삽입 순서(FIFO, 시간 우선)를 따른다. 매수 최우선은 최고가({@code lastEntry}),
 * 매도 최우선은 최저가({@code firstEntry})다.
 */
public final class OrderBook {

    private final Long2ObjectRBTreeMap<ArrayDeque<Order>> bids =
        new Long2ObjectRBTreeMap<>();
    private final Long2ObjectRBTreeMap<ArrayDeque<Order>> asks =
        new Long2ObjectRBTreeMap<>();

    public void insert(Order order) {
        Long2ObjectRBTreeMap<ArrayDeque<Order>> map = sideMap(order.side);

        ArrayDeque<Order> level = map.get(order.price);
        if (level == null) {
            level = new ArrayDeque<>();
            map.put(order.price, level);
        }

        level.addLast(order);
    }

    /**
     * 최우선 대기 주문을 제거 없이 반환한다. 반환된 객체는 호가창 내부 참조이므로,
     * 호출측이 {@code remaining}을 직접 감소시키면 제거/재삽입 없이 우선순위가 유지된다.
     * (rust-engine의 {@code peek_best_mut}에 대응.) 해당 측이 비어 있으면 {@code null}.
     */
    public Order peekBest(Side side) {
        ArrayDeque<Order> level = bestLevel(side);
        return level == null ? null : level.peekFirst();
    }

    /**
     * 최우선 대기 주문을 제거하고 반환한다. 해당 가격대가 비면 가격 키를 제거한다.
     * 해당 측이 비어 있으면 {@code null}. (rust-engine의 {@code poll_best}에 대응.)
     */
    public Order pollBest(Side side) {
        Long2ObjectRBTreeMap<ArrayDeque<Order>> map = sideMap(side);
        if (map.isEmpty()) {
            return null;
        }
        long price = bestPrice(side, map);
        ArrayDeque<Order> level = map.get(price);
        Order order = level.pollFirst();
        if (level.isEmpty()) {
            map.remove(price);
        }
        return order;
    }

    private ArrayDeque<Order> bestLevel(Side side) {
        Long2ObjectRBTreeMap<ArrayDeque<Order>> map = sideMap(side);
        if (map.isEmpty()) {
            return null;
        }
        return map.get(bestPrice(side, map));
    }

    private long bestPrice(
        Side side,
        Long2ObjectRBTreeMap<ArrayDeque<Order>> map
    ) {
        return side == Side.BUY ? map.lastLongKey() : map.firstLongKey();
    }

    private Long2ObjectRBTreeMap<ArrayDeque<Order>> sideMap(Side side) {
        return side == Side.BUY ? bids : asks;
    }
}
