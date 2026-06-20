package dev.junyoung.bench;

import java.util.Arrays;

/**
 * 레이턴시 샘플 기록기. rust-engine 하니스 {@code histogram.rs}의 {@code LatencyRecorder}를 포팅한다.
 *
 * <p>{@code long[]}을 정확한 용량으로 사전할당해 측정 중 재할당이 없도록 한다. percentile 인덱스는
 * Rust와 동일하게 {@code round((n-1)*p)}를 쓴다(양수이므로 {@link Math#round}가 Rust {@code f64::round}와 일치).
 */
public final class LatencyRecorder {
    private final long[] samples;
    private int size;
    private long sumNs;

    public LatencyRecorder(int capacity) {
        this.samples = new long[capacity];
    }

    public void record(long ns) {
        samples[size++] = ns;
        sumNs += ns;
    }

    public long sumNs() {
        return sumNs;
    }

    private static long percentileOf(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.round((sorted.length - 1) * p);
        return sorted[Math.min(idx, sorted.length - 1)];
    }

    public LatencySummary summary() {
        long[] sorted = Arrays.copyOf(samples, size);
        Arrays.sort(sorted);
        return new LatencySummary(
                percentileOf(sorted, 0.50),
                percentileOf(sorted, 0.90),
                percentileOf(sorted, 0.99),
                percentileOf(sorted, 0.999),
                size == 0 ? 0 : sorted[size - 1],
                size);
    }
}
