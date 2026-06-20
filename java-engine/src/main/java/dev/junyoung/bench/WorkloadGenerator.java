package dev.junyoung.bench;

import dev.junyoung.Order;
import dev.junyoung.Side;
import java.util.ArrayList;
import java.util.List;

/**
 * 벤치 워크로드 생성기. rust-engine 하니스의 {@code workload.rs}를 1:1 포팅한다.
 *
 * <p>동일 seed에서 Rust와 <b>바이트 단위로 동일한 주문열</b>을 생성하는 것이 목적이며, 이는 두 엔진
 * 비교의 공정성 전제다. 이를 위해 RNG 호출 순서 계약을 정확히 지킨다(README "RNG 호출 순서 계약").
 *
 * <p>언어 차이로 인한 필수 변환:
 * <ul>
 *   <li>Rust {@code u64 % n}은 부호 없는 나머지 → Java는 {@link Long#remainderUnsigned}를 쓴다
 *       ({@code long % n}은 음수에서 음수 결과라 byte 동일성이 깨진다).</li>
 *   <li>xorshift 2단계 {@code x >> 7}은 Rust에서 u64 logical shift → Java는 {@code >>>}를 쓴다.</li>
 *   <li>{@code is_multiple_of(2)}는 {@code (x & 1L) == 0} (부호 무관).</li>
 * </ul>
 */
public final class WorkloadGenerator {
    private static final long BASE_PRICE = 100_000L;
    private static final long SWEEP_LEVELS = 1_000L;
    private static final long SWEEP_MAKER_QTY = 1L;
    /** seed==0일 때 0 고착을 피하기 위한 골든레이쇼 상수. Rust {@code 0x9E37_79B9_7F4A_7C15}. */
    private static final long GOLDEN_RATIO = 0x9E3779B97F4A7C15L;

    private final Scenario scenario;
    private final long count;
    private long rngState;
    private long nextId;
    private long totalEmitted;
    private Side crossMakerSide;

    public WorkloadGenerator(Scenario scenario, long seed, long count) {
        this.scenario = scenario;
        this.count = count;
        this.rngState = (seed == 0) ? GOLDEN_RATIO : seed;
        this.nextId = 1;
        this.totalEmitted = 0;
        this.crossMakerSide = Side.SELL;
    }

    public boolean isExhausted() {
        return totalEmitted >= count;
    }

    public List<Order> nextBatch(int batchSize) {
        long remaining = count - totalEmitted;
        int n = (int) Math.min((long) batchSize, remaining);
        List<Order> batch = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            batch.add(nextOrder());
        }
        return batch;
    }

    /** xorshift64. {@code >>> 7}(logical shift)에 주의. */
    private long nextRand() {
        long x = rngState;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        rngState = x;
        return x;
    }

    private Order nextOrder() {
        Order order = switch (scenario) {
            case THIN_BOOK -> nextThinBookOrder();
            case ACTIVE_FILL -> nextActiveFillOrder();
            case DEEP_SWEEP_CROSS -> nextDeepSweepCrossOrder();
            case BOOK_GROWTH_WORST -> nextBookGrowthWorstOrder();
        };
        totalEmitted += 1;
        return order;
    }

    /** id(=sequence) 선할당. 난수 소비 전에 발급하며 시나리오·체결과 무관하게 매 주문 +1. */
    private long allocId() {
        long id = nextId;
        nextId += 1;
        return id;
    }

    private Order nextThinBookOrder() {
        long id = allocId();
        Side side = (nextRand() & 1L) == 0 ? Side.BUY : Side.SELL;
        long offset = 1_000 + Long.remainderUnsigned(nextRand(), 1_000);
        long price = (side == Side.BUY) ? BASE_PRICE - offset : BASE_PRICE + offset;
        long qty = 10 + Long.remainderUnsigned(nextRand(), 90);
        return order(id, side, price, qty);
    }

    private Order nextActiveFillOrder() {
        long id = allocId();
        Side side = (nextRand() & 1L) == 0 ? Side.BUY : Side.SELL;
        long jitter = Long.remainderUnsigned(nextRand(), 5) - 2;
        long price = BASE_PRICE + jitter;
        long qty = 10 + Long.remainderUnsigned(nextRand(), 90);
        return order(id, side, price, qty);
    }

    private Order nextBookGrowthWorstOrder() {
        long id = allocId();
        long level = (totalEmitted / 2) + 1;
        Side side = (totalEmitted & 1L) == 0 ? Side.BUY : Side.SELL;
        long price = (side == Side.BUY) ? BASE_PRICE - level : BASE_PRICE + level;
        return order(id, side, price, 1);
    }

    /**
     * DeepSweepCross. 난수를 전혀 소비하지 않는 완전 결정적 워크로드(nextId만 증가).
     * {@code cyclePos}는 {@code totalEmitted}(생성 직후 증가 전, 현재 주문의 0-based 인덱스)로 계산한다.
     */
    private Order nextDeepSweepCrossOrder() {
        long id = allocId();
        long cyclePos = totalEmitted % (SWEEP_LEVELS + 1);
        Side makerSide = crossMakerSide;

        if (cyclePos < SWEEP_LEVELS) {
            long level = cyclePos;
            long price = (makerSide == Side.BUY) ? BASE_PRICE - level : BASE_PRICE + level;
            return order(id, makerSide, price, SWEEP_MAKER_QTY);
        }

        // sweep taker: maker_side를 반전 저장해 다음 사이클 방향을 바꾸고, 그 반대편으로 큰 taker를 낸다.
        crossMakerSide = makerSide.opposite();
        Side takerSide = makerSide.opposite();
        long price = (takerSide == Side.BUY) ? BASE_PRICE + SWEEP_LEVELS : BASE_PRICE - SWEEP_LEVELS;
        return order(id, takerSide, price, SWEEP_LEVELS * SWEEP_MAKER_QTY);
    }

    private static Order order(long id, Side side, long price, long quantity) {
        return new Order(id, side, price, quantity, quantity, id);
    }
}
