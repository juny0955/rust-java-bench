package dev.junyoung.bench;

/** 레이턴시 요약. rust-engine 하니스 {@code histogram.rs}의 {@code LatencySummary}와 동일하다. */
public record LatencySummary(long p50, long p90, long p99, long p999, long max, int count) {}
