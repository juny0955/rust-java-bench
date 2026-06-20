package dev.junyoung.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.junyoung.MatchingEngine;
import dev.junyoung.Order;
import dev.junyoung.Side;
import dev.junyoung.Trade;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 워크로드 생성기 동일성 테스트. 핵심은 <b>Rust 생성기에서 덤프한 골든값</b>과의 크로스 검증으로,
 * 같은 seed에서 두 언어가 바이트 단위로 동일한 주문열을 만드는지 확인한다.
 */
class WorkloadGeneratorTest {

    /** 골든 행: {@code id, side, price, quantity}. remaining==quantity, sequence==id 는 별도 단언. */
    private record Row(long id, String side, long price, long qty) {}

    private static List<Order> collectAll(Scenario scenario, long seed, long count, int batchSize) {
        WorkloadGenerator g = new WorkloadGenerator(scenario, seed, count);
        List<Order> all = new ArrayList<>();
        while (!g.isExhausted()) {
            all.addAll(g.nextBatch(batchSize));
        }
        return all;
    }

    private static void assertMatches(Order o, Row r) {
        assertEquals(r.id(), o.id, "id");
        assertEquals(r.side(), o.side.name(), "side");
        assertEquals(r.price(), o.price, "price");
        assertEquals(r.qty(), o.quantity, "quantity");
        assertEquals(r.qty(), o.remaining, "remaining(=quantity at creation)");
        assertEquals(r.id(), o.sequence, "sequence(=id)");
    }

    // --- Rust 골든값 (rust-engine workload.rs 생성기에서 덤프) ---

    private static final Row[] THIN_BOOK_123 = {
        new Row(1, "SELL", 101870, 93), new Row(2, "BUY", 98562, 95),
        new Row(3, "SELL", 101747, 20), new Row(4, "SELL", 101801, 68),
        new Row(5, "BUY", 98760, 33), new Row(6, "BUY", 98927, 41),
        new Row(7, "SELL", 101184, 22), new Row(8, "BUY", 98287, 70),
        new Row(9, "BUY", 98505, 44), new Row(10, "BUY", 98504, 13),
        new Row(11, "BUY", 98197, 58), new Row(12, "BUY", 98263, 78),
        new Row(13, "SELL", 101715, 43), new Row(14, "BUY", 98822, 75),
        new Row(15, "SELL", 101493, 92), new Row(16, "BUY", 98406, 60),
        new Row(17, "BUY", 98395, 15), new Row(18, "BUY", 98800, 59),
        new Row(19, "BUY", 98086, 87), new Row(20, "BUY", 98846, 97),
    };

    private static final Row[] ACTIVE_FILL_123 = {
        new Row(1, "SELL", 99998, 93), new Row(2, "BUY", 100001, 95),
        new Row(3, "SELL", 100000, 20), new Row(4, "SELL", 99999, 68),
        new Row(5, "BUY", 99998, 33), new Row(6, "BUY", 100001, 41),
        new Row(7, "SELL", 100002, 22), new Row(8, "BUY", 100001, 70),
        new Row(9, "BUY", 99998, 44), new Row(10, "BUY", 99999, 13),
        new Row(11, "BUY", 100001, 58), new Row(12, "BUY", 100000, 78),
        new Row(13, "SELL", 99998, 43), new Row(14, "BUY", 100001, 75),
        new Row(15, "SELL", 100001, 92), new Row(16, "BUY", 100002, 60),
        new Row(17, "BUY", 99998, 15), new Row(18, "BUY", 99998, 59),
        new Row(19, "BUY", 100002, 87), new Row(20, "BUY", 100002, 97),
    };

    @Test
    @DisplayName("ThinBook seed=123 주문열이 Rust 골든값과 바이트 단위로 일치한다")
    void thinBookMatchesRustGolden() {
        List<Order> orders = collectAll(Scenario.THIN_BOOK, 123, THIN_BOOK_123.length, 7);
        assertEquals(THIN_BOOK_123.length, orders.size());
        for (int i = 0; i < THIN_BOOK_123.length; i++) {
            assertMatches(orders.get(i), THIN_BOOK_123[i]);
        }
    }

    @Test
    @DisplayName("ActiveFill seed=123 주문열이 Rust 골든값과 바이트 단위로 일치한다")
    void activeFillMatchesRustGolden() {
        List<Order> orders = collectAll(Scenario.ACTIVE_FILL, 123, ACTIVE_FILL_123.length, 7);
        assertEquals(ACTIVE_FILL_123.length, orders.size());
        for (int i = 0; i < ACTIVE_FILL_123.length; i++) {
            assertMatches(orders.get(i), ACTIVE_FILL_123[i]);
        }
    }

    @Test
    @DisplayName("DeepSweepCross label과 legacy parse alias를 지원한다")
    void deepSweepCrossLabelAndLegacyAlias() {
        assertEquals("DeepSweepCross", Scenario.DEEP_SWEEP_CROSS.label());
        assertEquals(Scenario.DEEP_SWEEP_CROSS, Scenario.fromLabel("DeepSweepCross"));
        assertEquals(Scenario.DEEP_SWEEP_CROSS, Scenario.fromLabel("WorstCaseCross"));
    }

    @Test
    @DisplayName("BookGrowthWorst label과 Rust 규칙 주문열을 지원한다")
    void bookGrowthWorstLabelAndRustRuleSequence() {
        Scenario scenario = Scenario.fromLabel("BookGrowthWorst");
        assertEquals("BookGrowthWorst", scenario.label());

        Row[] expected = {
            new Row(1, "BUY", 99999, 1),
            new Row(2, "SELL", 100001, 1),
            new Row(3, "BUY", 99998, 1),
            new Row(4, "SELL", 100002, 1),
            new Row(5, "BUY", 99997, 1),
            new Row(6, "SELL", 100003, 1),
        };
        List<Order> orders = collectAll(scenario, 123, expected.length, 3);
        assertEquals(expected.length, orders.size());
        for (int i = 0; i < expected.length; i++) {
            assertMatches(orders.get(i), expected[i]);
        }
    }

    @Test
    @DisplayName("BookGrowthWorst는 seed와 무관하고 모든 주문이 미체결로 book을 키운다")
    void bookGrowthWorstIsSeedIndependentAndNeverCrosses() {
        Scenario scenario = Scenario.fromLabel("BookGrowthWorst");
        List<Order> seedA = collectAll(scenario, 1, 256, 17);
        List<Order> seedB = collectAll(scenario, 987654321, 256, 64);
        MatchingEngine engine = new MatchingEngine();
        Set<Long> buyPrices = new HashSet<>();
        Set<Long> sellPrices = new HashSet<>();

        assertEquals(seedA.size(), seedB.size());
        for (int i = 0; i < seedA.size(); i++) {
            Order a = seedA.get(i);
            Order b = seedB.get(i);
            assertEquals(a.id, b.id);
            assertEquals(a.side, b.side);
            assertEquals(a.price, b.price);
            assertEquals(1, a.quantity);
            assertEquals(a.remaining, b.remaining);
            assertEquals(a.sequence, b.sequence);
            assertTrue(engine.submitLimitOrder(a).isEmpty(), "BookGrowthWorst must never cross at index " + i);
            if (a.side == Side.BUY) {
                assertTrue(a.price < 100_000);
                assertTrue(buyPrices.add(a.price), "duplicate buy price " + a.price);
            } else {
                assertTrue(a.price > 100_000);
                assertTrue(sellPrices.add(a.price), "duplicate sell price " + a.price);
            }
        }
        assertEquals(128, buyPrices.size());
        assertEquals(128, sellPrices.size());
    }

    @Test
    @DisplayName("DeepSweepCross 주요 인덱스(maker/taker/방향전환)가 Rust 골든값과 일치한다")
    void deepSweepCrossMatchesRustGoldenAtKeyIndices() {
        long count = 2003;
        List<Order> orders = collectAll(Scenario.DEEP_SWEEP_CROSS, 7, count, 4096);
        assertEquals(count, orders.size());

        assertMatches(orders.get(0), new Row(1, "SELL", 100000, 1));
        assertMatches(orders.get(1), new Row(2, "SELL", 100001, 1));
        assertMatches(orders.get(2), new Row(3, "SELL", 100002, 1));
        assertMatches(orders.get(999), new Row(1000, "SELL", 100999, 1));
        // 사이클 경계: maker_side=SELL의 큰 taker(BUY, BASE+1000), 수량 1000
        assertMatches(orders.get(1000), new Row(1001, "BUY", 101000, 1000));
        // 방향 전환 후 다음 사이클 maker(BUY, level 0/1)
        assertMatches(orders.get(1001), new Row(1002, "BUY", 100000, 1));
        assertMatches(orders.get(1002), new Row(1003, "BUY", 99999, 1));
        // 두 번째 사이클의 taker는 반대 방향(SELL, BASE-1000)
        assertMatches(orders.get(2001), new Row(2002, "SELL", 99000, 1000));
        assertMatches(orders.get(2002), new Row(2003, "SELL", 100000, 1));
    }

    @Test
    @DisplayName("같은 seed면 배치 크기와 무관하게 동일한 주문열을 생성한다")
    void sameSeedSameSequenceRegardlessOfBatchSize() {
        for (Scenario scenario : new Scenario[] {Scenario.THIN_BOOK, Scenario.DEEP_SWEEP_CROSS}) {
            List<Order> small = collectAll(scenario, 123, 10_000, 1_000);
            List<Order> large = collectAll(scenario, 123, 10_000, 100_000);
            assertEquals(small.size(), large.size());
            for (int i = 0; i < small.size(); i++) {
                Order a = small.get(i);
                Order b = large.get(i);
                assertEquals(a.id, b.id);
                assertEquals(a.side, b.side);
                assertEquals(a.price, b.price);
                assertEquals(a.quantity, b.quantity);
                assertEquals(a.remaining, b.remaining);
                assertEquals(a.sequence, b.sequence);
            }
        }
    }

    @Test
    @DisplayName("정확히 count개 생성 후 소진된다")
    void exhaustsAfterExactCount() {
        WorkloadGenerator g = new WorkloadGenerator(Scenario.THIN_BOOK, 42, 10);
        assertFalse(g.isExhausted());
        assertEquals(7, g.nextBatch(7).size());
        assertFalse(g.isExhausted());
        assertEquals(3, g.nextBatch(7).size());
        assertTrue(g.isExhausted());
    }

    @Test
    @DisplayName("DeepSweepCross의 단일 taker가 여러 가격대를 sweep한다")
    void deepSweepCrossSweepsMultiplePriceLevels() {
        WorkloadGenerator g = new WorkloadGenerator(Scenario.DEEP_SWEEP_CROSS, 99, 3_003);
        MatchingEngine engine = new MatchingEngine();
        int maxTradesForOneOrder = 0;
        boolean crossedMultiplePrices = false;

        while (!g.isExhausted()) {
            for (Order order : g.nextBatch(128)) {
                List<Trade> trades = engine.submitLimitOrder(order);
                maxTradesForOneOrder = Math.max(maxTradesForOneOrder, trades.size());
                if (trades.size() >= 2) {
                    long firstPrice = trades.get(0).price();
                    crossedMultiplePrices = trades.stream().anyMatch(t -> t.price() != firstPrice);
                }
            }
        }

        assertTrue(maxTradesForOneOrder >= 1000,
                "sweep taker가 많은 체결을 내야 한다, got " + maxTradesForOneOrder);
        assertTrue(crossedMultiplePrices);
    }
}
