package dev.junyoung.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** rust-engine 하니스 histogram.rs 단위 테스트를 포팅한다. */
class LatencyRecorderTest {

    @Test
    @DisplayName("사전할당 용량만큼 기록하고 합계를 누적한다")
    void recordsUpToCapacityAndAccumulatesSum() {
        LatencyRecorder recorder = new LatencyRecorder(100);
        long expectedSum = 0;
        for (long i = 0; i < 100; i++) {
            recorder.record(i);
            expectedSum += i;
        }
        assertEquals(expectedSum, recorder.sumNs());
        assertEquals(100, recorder.summary().count());
    }

    @Test
    @DisplayName("summary는 정렬된 percentile을 반환한다")
    void summaryReturnsOrderedPercentiles() {
        LatencyRecorder recorder = new LatencyRecorder(10);
        for (long v : new long[] {10, 1, 5, 9, 2, 8, 3, 7, 4, 6}) {
            recorder.record(v);
        }
        LatencySummary s = recorder.summary();
        assertEquals(10, s.count());
        assertEquals(10, s.max());
        assertTrue(s.p50() <= s.p90());
        assertTrue(s.p90() <= s.p99());
        assertTrue(s.p99() <= s.p999());
        assertTrue(s.p999() <= s.max());
    }

    @Test
    @DisplayName("percentile 인덱스 round((n-1)*p)가 Rust와 동일하다")
    void percentileIndexMatchesRust() {
        LatencyRecorder recorder = new LatencyRecorder(10);
        for (long v = 1; v <= 10; v++) {
            recorder.record(v);
        }
        // sorted=[1..10], n=10: idx=round(9*p)
        LatencySummary s = recorder.summary();
        assertEquals(6, s.p50()); // round(4.5)=5 -> sorted[5]=6
        assertEquals(9, s.p90()); // round(8.1)=8 -> sorted[8]=9
        assertEquals(10, s.p99()); // round(8.91)=9 -> sorted[9]=10
        assertEquals(10, s.p999()); // round(8.991)=9 -> sorted[9]=10
        assertEquals(10, s.max());
    }
}
