package dev.junyoung.bench;

import dev.junyoung.MatchingEngine;
import dev.junyoung.Order;
import dev.junyoung.bench.JvmDiagnostics.Snapshot;
import java.io.IOException;
import java.lang.management.CompilationMXBean;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.function.ToDoubleFunction;

/**
 * 자체 벤치마크 러너. rust-engine 하니스 {@code main.rs}를 포팅하고, 여기에 JVM 병목/리소스 진단
 * 컬럼을 더한다. 외부 벤치마크 프레임워크 없이 JDK 표준만 사용한다.
 *
 * <p>Rust와 동일한 3-pass(throughput+진단 / latency / RSS-서브프로세스)를 유지하되, GC·JIT·할당·힙
 * 진단은 throughput pass에 통합 측정한다. Rust의 17개 CSV 컬럼은 순서·이름 그대로 두고 6개 진단
 * 컬럼을 {@code target_os} 뒤에 append한다(앞 17컬럼은 Rust 헤더와 byte 동일).
 *
 * <p>실행: {@code ./gradlew bench}. RSS는 Linux에서만 별도 워커 프로세스로 측정한다.
 */
public final class BenchRunner {
    private static final long WARMUP_MIN = 10_000;
    private static final long WARMUP_DIVISOR = 10;
    private static final int MAX_WARMUP_PASSES = 10;
    private static final int STABLE_STREAK = 2;
    private static final long POST_WARMUP_SLEEP_MS = 150;
    private static final int REPS = 10;
    private static final int BATCH_SIZE = 100_000;
    private static final long TARGET_LATENCY_SAMPLES = 100_000;
    private static final int TIMER_ITERS = 2_000_000;
    private static final String MEM_WORKER_FLAG = "--mem-worker";
    private static final long[] SCALES = {1_000_000L, 10_000_000L};
    private static final Scenario[] SCENARIOS = {
        Scenario.THIN_BOOK, Scenario.ACTIVE_FILL, Scenario.DEEP_SWEEP_CROSS, Scenario.BOOK_GROWTH_WORST
    };
    private static final String CSV_HEADER =
            "scenario,scale,row_type,ops_sec,submit_elapsed_ms,p50_ns,p90_ns,p99_ns,p999_ns,max_ns,"
            + "latency_sample_count,latency_sample_stride,baseline_rss_kb,peak_rss_kb,avg_rss_kb,"
            + "rss_supported,target_os,gc_count,gc_time_ms,jit_compile_ms,alloc_bytes,"
            + "heap_used_peak_kb,heap_used_avg_kb";

    /** black_box 대체: trade 참조를 escape시켜 JIT 할당 제거를 막는다(§73 할당비용 포함). */
    static volatile Object REF_SINK;
    /** black_box 대체: 타이머 오버헤드 루프의 long delta(autobox 회피용 primitive 싱크). */
    static volatile long LONG_SINK;

    private BenchRunner() {}

    // --- 측정 결과 모델 ---

    /** 한 행의 모든 지표(double). 옵셔널 컬럼은 OptionalDouble. Rust {@code MetricRow}에 대응. */
    record MetricRow(
            double opsSec, double submitElapsedMs,
            double p50, double p90, double p99, double p999, double max,
            double latencySampleCount, double latencySampleStride,
            OptionalDouble baselineRssKb, OptionalDouble peakRssKb, OptionalDouble avgRssKb,
            boolean rssSupported, String targetOs,
            double gcCount, double gcTimeMs, double jitCompileMs,
            OptionalDouble allocBytes, double heapUsedPeakKb, double heapUsedAvgKb) {}

    private record ThroughputResult(
            long submitElapsedNanos, long gcCount, long gcTimeMs, long jitCompileMs,
            OptionalLong allocBytes, long heapUsedPeakKb, double heapUsedAvgKb) {}

    /** RSS 워커 결과. Rust {@code MemResult} 4-필드와 동일(baseline, peak, avg, supported). */
    private record MemResult(
            OptionalLong baselineRssKb, OptionalLong peakRssKb, OptionalLong avgRssKb,
            boolean supported) {
        static MemResult unsupported() {
            return new MemResult(OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(), false);
        }
    }

    // --- 측정 pass ---

    private static long warmupCountFor(long scale) {
        return Math.max(scale / WARMUP_DIVISOR, WARMUP_MIN);
    }

    /**
     * 타이머 패턴 자체의 샘플당 비용(nanoTime 두 번 = now()+elapsed()에 대응)을 측정한다.
     * 레이턴시 percentile은 이 고정 오버헤드를 포함한 수치로 읽어야 한다.
     */
    static double measureTimerOverheadNs() {
        long start = System.nanoTime();
        for (int i = 0; i < TIMER_ITERS; i++) {
            long t0 = System.nanoTime();
            LONG_SINK = System.nanoTime() - t0;
        }
        long elapsed = System.nanoTime() - start;
        return (double) elapsed / TIMER_ITERS;
    }

    /**
     * JIT 컴파일 시간 델타가 연속 {@link #STABLE_STREAK}회 0ms로 안정화될 때까지(최대
     * {@link #MAX_WARMUP_PASSES}패스) warmup pass를 반복한다. 고정 1-pass warmup은 C2까지
     * 도달하지 못해 측정 초반(run_0/run_1) ops_sec이 낮고 불안정해지는 문제가 있었다.
     *
     * <p>각 pass는 엔진 submit뿐 아니라 {@code runOnce}가 실제로 타는 레이턴시 기록·진단 래퍼
     * 코드 경로까지 동일하게 실행한다(RSS 서브프로세스 측정은 제외). 단순 submit 루프만 워밍업하면
     * 그 두 코드 경로가 {@code run_0}에서 처음 실행되어 그때 비로소 JIT 컴파일되는 문제가 있었다.
     */
    private static void runWarmup(Scenario scenario, long warmupCount) {
        CompilationMXBean compilation = ManagementFactory.getCompilationMXBean();
        boolean jitTrackable = compilation != null && compilation.isCompilationTimeMonitoringSupported();

        int passes = 0;
        int stableStreak = 0;
        long prevCompileMs = jitTrackable ? compilation.getTotalCompilationTime() : 0;
        while (passes < MAX_WARMUP_PASSES) {
            runWarmupPass(scenario, warmupCount);
            passes++;
            if (!jitTrackable) {
                continue;
            }
            long compileMs = compilation.getTotalCompilationTime();
            long delta = compileMs - prevCompileMs;
            prevCompileMs = compileMs;
            stableStreak = (delta == 0) ? stableStreak + 1 : 0;
            if (stableStreak >= STABLE_STREAK) {
                break;
            }
        }

        try {
            Thread.sleep(POST_WARMUP_SLEEP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.printf(
                "warmup passes: %d (%s, +%dms settle)%n",
                passes, stableStreak >= STABLE_STREAK ? "stabilized" : "max reached", POST_WARMUP_SLEEP_MS);
    }

    private static void runWarmupPass(Scenario scenario, long warmupCount) {
        runThroughput(scenario, warmupCount, 0xDEAD_BEEFL, new JvmDiagnostics());
        long stride = Math.max(warmupCount / TARGET_LATENCY_SAMPLES, 1);
        runLatency(scenario, warmupCount, 0xDEAD_BEEFL, stride);
    }

    /**
     * 처리량 + JVM 진단 통합 pass. submit 루프만 wall-clock으로 측정하고(주문 생성·레이턴시 기록 제외),
     * gc/jit는 pass 전체 delta, alloc은 submit 구간만 누적, 힙은 batch 사이 적재 상태 샘플로 잡는다.
     */
    private static ThroughputResult runThroughput(
            Scenario scenario, long count, long seed, JvmDiagnostics diag) {
        MatchingEngine engine = new MatchingEngine();
        WorkloadGenerator gen = new WorkloadGenerator(scenario, seed, count);
        long submitElapsed = 0;
        long submitted = 0;
        long heapPeakKb = 0;
        long heapSum = 0;
        int heapSamples = 0;
        long allocAccum = 0;
        boolean allocOk = true;

        Snapshot startDiag = diag.snapshot();
        while (!gen.isExhausted()) {
            List<Order> batch = gen.nextBatch(BATCH_SIZE);
            submitted += batch.size();

            OptionalLong allocBefore = diag.snapshot().allocBytes();
            long submitStart = System.nanoTime();
            for (Order order : batch) {
                REF_SINK = engine.submitLimitOrder(order);
            }
            submitElapsed += System.nanoTime() - submitStart;
            OptionalLong allocAfter = diag.snapshot().allocBytes();
            if (allocBefore.isPresent() && allocAfter.isPresent()) {
                allocAccum += allocAfter.getAsLong() - allocBefore.getAsLong();
            } else {
                allocOk = false;
            }

            long heap = diag.heapUsedKb();
            heapPeakKb = Math.max(heapPeakKb, heap);
            heapSum += heap;
            heapSamples++;
        }
        Snapshot endDiag = diag.snapshot();

        if (submitted != count) {
            throw new IllegalStateException("submitted " + submitted + " != count " + count);
        }
        if (submitElapsed <= 0) {
            throw new IllegalStateException("submit elapsed must be positive");
        }

        double heapAvg = heapSamples == 0 ? 0 : (double) heapSum / heapSamples;
        OptionalLong alloc = allocOk ? OptionalLong.of(allocAccum) : OptionalLong.empty();
        return new ThroughputResult(
                submitElapsed,
                endDiag.gcCount() - startDiag.gcCount(),
                endDiag.gcTimeMs() - startDiag.gcTimeMs(),
                endDiag.jitCompileMs() - startDiag.jitCompileMs(),
                alloc, heapPeakKb, heapAvg);
    }

    private static LatencySummary runLatency(
            Scenario scenario, long count, long seed, long sampleStride) {
        MatchingEngine engine = new MatchingEngine();
        WorkloadGenerator gen = new WorkloadGenerator(scenario, seed, count);
        int sampleCapacity = (int) ceilDiv(count, sampleStride);
        LatencyRecorder recorder = new LatencyRecorder(sampleCapacity);
        long seen = 0;

        while (!gen.isExhausted()) {
            for (Order order : gen.nextBatch(BATCH_SIZE)) {
                if (seen % sampleStride == 0) {
                    long t0 = System.nanoTime();
                    REF_SINK = engine.submitLimitOrder(order);
                    recorder.record(System.nanoTime() - t0);
                } else {
                    REF_SINK = engine.submitLimitOrder(order);
                }
                seen++;
            }
        }

        if (seen != count) {
            throw new IllegalStateException("seen " + seen + " != count " + count);
        }
        LatencySummary summary = recorder.summary();
        if (summary.count() != sampleCapacity) {
            throw new IllegalStateException(
                    "latency samples " + summary.count() + " != capacity " + sampleCapacity);
        }
        return summary;
    }

    /** RSS 측정 pass(워커 프로세스 내부). Rust {@code run_memory}의 RSS 부분을 포팅. */
    private static MemResult runMemory(Scenario scenario, long count, long seed) {
        MatchingEngine engine = new MatchingEngine();
        WorkloadGenerator gen = new WorkloadGenerator(scenario, seed, count);
        boolean supported = Rss.isSupported();
        OptionalLong baseline = Rss.readRssKb();
        List<Long> samples = new ArrayList<>();

        while (!gen.isExhausted()) {
            for (Order order : gen.nextBatch(BATCH_SIZE)) {
                REF_SINK = engine.submitLimitOrder(order);
            }
            Rss.readRssKb().ifPresent(samples::add);
        }

        OptionalLong peak = samples.stream().mapToLong(Long::longValue).max();
        OptionalLong avg = samples.isEmpty()
                ? OptionalLong.empty()
                : OptionalLong.of(samples.stream().mapToLong(Long::longValue).sum() / samples.size());
        return new MemResult(baseline, peak, avg, supported);
    }

    /**
     * RSS 측정을 별도 워커 프로세스로 격리한다(깨끗한 baseline). best-effort: RSS 미지원(예: darwin)이면
     * 서브프로세스 없이 빈 결과를 반환하고, 워커 실행이 실패해도 RSS 컬럼만 비우고 진행한다.
     */
    private static MemResult measureMemoryIsolated(Scenario scenario, long count, long seed) {
        if (!Rss.isSupported()) {
            return MemResult.unsupported();
        }
        try {
            String javaBin = System.getProperty("java.home") + "/bin/java";
            String classpath = System.getProperty("java.class.path");
            Process process = new ProcessBuilder(
                    javaBin, "-cp", classpath, BenchRunner.class.getName(),
                    MEM_WORKER_FLAG, scenario.label(),
                    Long.toString(count), Long.toString(seed))
                    .start();
            String stdout = new String(process.getInputStream().readAllBytes()).strip();
            int code = process.waitFor();
            if (code != 0 || stdout.isEmpty()) {
                return MemResult.unsupported();
            }
            return parseMemResult(stdout.lines().findFirst().orElse(""));
        } catch (IOException | InterruptedException e) {
            return MemResult.unsupported();
        }
    }

    private static String serializeMemResult(MemResult r) {
        return optLongStr(r.baselineRssKb()) + "," + optLongStr(r.peakRssKb()) + ","
                + optLongStr(r.avgRssKb()) + "," + r.supported();
    }

    private static MemResult parseMemResult(String line) {
        String[] f = line.strip().split(",", -1);
        if (f.length != 4) {
            return MemResult.unsupported();
        }
        return new MemResult(parseOptLong(f[0]), parseOptLong(f[1]), parseOptLong(f[2]),
                Boolean.parseBoolean(f[3]));
    }

    // --- 한 run / 집계 ---

    private static MetricRow runOnce(Scenario scenario, long count, long seed, String targetOs) {
        long stride = Math.max(count / TARGET_LATENCY_SAMPLES, 1);
        JvmDiagnostics diag = new JvmDiagnostics();
        ThroughputResult tp = runThroughput(scenario, count, seed, diag);
        LatencySummary lat = runLatency(scenario, count, seed, stride);
        MemResult mem = measureMemoryIsolated(scenario, count, seed);

        double opsSec = count / (tp.submitElapsedNanos() / 1_000_000_000.0);
        double submitMs = tp.submitElapsedNanos() / 1_000_000.0;
        return new MetricRow(
                opsSec, submitMs,
                lat.p50(), lat.p90(), lat.p99(), lat.p999(), lat.max(),
                lat.count(), stride,
                optLongToDouble(mem.baselineRssKb()), optLongToDouble(mem.peakRssKb()),
                optLongToDouble(mem.avgRssKb()), mem.supported(), targetOs,
                tp.gcCount(), tp.gcTimeMs(), tp.jitCompileMs(),
                optLongToDouble(tp.allocBytes()), tp.heapUsedPeakKb(), tp.heapUsedAvgKb());
    }

    static double[] meanStddev(double[] values) {
        int n = values.length;
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        double mean = sum / n;
        double variance = 0;
        for (double v : values) {
            variance += (v - mean) * (v - mean);
        }
        variance /= n; // 모표준편차(분모 n)
        return new double[] {mean, Math.sqrt(variance)};
    }

    private static double[] aggregate(List<MetricRow> runs, ToDoubleFunction<MetricRow> pick) {
        return meanStddev(runs.stream().mapToDouble(pick).toArray());
    }

    /** OptionalDouble 컬럼 집계. 존재하는 값만 모아 평균/표준편차; 전부 없으면 둘 다 empty. */
    private static OptionalDouble[] aggregateOptional(
            List<MetricRow> runs, java.util.function.Function<MetricRow, OptionalDouble> pick) {
        double[] present = runs.stream()
                .map(pick).filter(OptionalDouble::isPresent)
                .mapToDouble(OptionalDouble::getAsDouble).toArray();
        if (present.length == 0) {
            return new OptionalDouble[] {OptionalDouble.empty(), OptionalDouble.empty()};
        }
        double[] ms = meanStddev(present);
        return new OptionalDouble[] {OptionalDouble.of(ms[0]), OptionalDouble.of(ms[1])};
    }

    private static MetricRow buildAggregateRow(List<MetricRow> runs, boolean stddev, String targetOs) {
        int i = stddev ? 1 : 0;
        OptionalDouble[] baseline = aggregateOptional(runs, MetricRow::baselineRssKb);
        OptionalDouble[] peak = aggregateOptional(runs, MetricRow::peakRssKb);
        OptionalDouble[] avg = aggregateOptional(runs, MetricRow::avgRssKb);
        OptionalDouble[] alloc = aggregateOptional(runs, MetricRow::allocBytes);
        boolean rssSupported = runs.stream().anyMatch(MetricRow::rssSupported);
        return new MetricRow(
                aggregate(runs, MetricRow::opsSec)[i], aggregate(runs, MetricRow::submitElapsedMs)[i],
                aggregate(runs, MetricRow::p50)[i], aggregate(runs, MetricRow::p90)[i],
                aggregate(runs, MetricRow::p99)[i], aggregate(runs, MetricRow::p999)[i],
                aggregate(runs, MetricRow::max)[i],
                aggregate(runs, MetricRow::latencySampleCount)[i],
                aggregate(runs, MetricRow::latencySampleStride)[i],
                baseline[i], peak[i], avg[i], rssSupported, targetOs,
                aggregate(runs, MetricRow::gcCount)[i], aggregate(runs, MetricRow::gcTimeMs)[i],
                aggregate(runs, MetricRow::jitCompileMs)[i], alloc[i],
                aggregate(runs, MetricRow::heapUsedPeakKb)[i],
                aggregate(runs, MetricRow::heapUsedAvgKb)[i]);
    }

    // --- 출력 ---

    static String csvHeader() {
        return CSV_HEADER;
    }

    static String csvRow(String scenario, long scale, String rowType, MetricRow m) {
        return String.join(",",
                scenario, Long.toString(scale), rowType,
                num(m.opsSec()), num(m.submitElapsedMs()),
                num(m.p50()), num(m.p90()), num(m.p99()), num(m.p999()), num(m.max()),
                num(m.latencySampleCount()), num(m.latencySampleStride()),
                csvOptional(m.baselineRssKb()), csvOptional(m.peakRssKb()), csvOptional(m.avgRssKb()),
                Boolean.toString(m.rssSupported()), m.targetOs(),
                num(m.gcCount()), num(m.gcTimeMs()), num(m.jitCompileMs()),
                csvOptional(m.allocBytes()), num(m.heapUsedPeakKb()), num(m.heapUsedAvgKb()));
    }

    private static void printRow(String label, MetricRow m) {
        System.out.printf(
                "  %-7s: ops_sec=%s submit=%sms p50=%sns p99=%sns max=%sns gc=%s/%sms jit=%sms "
                + "alloc=%s heap_peak=%sKB heap_avg=%sKB rss[base/peak/avg]=%s/%s/%s%n",
                label, num(m.opsSec()), num(m.submitElapsedMs()),
                num(m.p50()), num(m.p99()), num(m.max()),
                num(m.gcCount()), num(m.gcTimeMs()), num(m.jitCompileMs()),
                displayOptional(m.allocBytes()),
                num(m.heapUsedPeakKb()), num(m.heapUsedAvgKb()),
                displayOptional(m.baselineRssKb()), displayOptional(m.peakRssKb()),
                displayOptional(m.avgRssKb()));
    }

    // --- 진입점 ---

    public static void main(String[] args) {
        List<String> argList = List.of(args);
        int memIdx = argList.indexOf(MEM_WORKER_FLAG);
        if (memIdx >= 0) {
            runMemWorker(argList.subList(memIdx + 1, argList.size()));
            return;
        }

        guardJitEnabled();

        double timerOverheadNs = measureTimerOverheadNs();
        System.out.printf(
                "timer_overhead_ns (System.nanoTime x2 per latency sample): %.1f%n", timerOverheadNs);
        System.out.println(
                "latency percentiles include this per-sample timer overhead; subtract it for an "
                + "engine-only estimate.\n");

        String targetOs = normalizedOs();
        List<String> csvRows = new ArrayList<>();
        csvRows.add(csvHeader());

        for (Scenario scenario : SCENARIOS) {
            for (long scale : SCALES) {
                System.out.printf("== %s / %d ==%n", scenario.label(), scale);
                runWarmup(scenario, warmupCountFor(scale));

                List<MetricRow> runs = new ArrayList<>(REPS);
                for (int rep = 0; rep < REPS; rep++) {
                    long seed = 0x1234_5678_9ABC_DEF0L + rep; // long overflow = Rust wrapping_add
                    MetricRow row = runOnce(scenario, scale, seed, targetOs);
                    printRow("run_" + rep, row);
                    csvRows.add(csvRow(scenario.label(), scale, "run_" + rep, row));
                    runs.add(row);
                }

                MetricRow meanRow = buildAggregateRow(runs, false, targetOs);
                MetricRow stddevRow = buildAggregateRow(runs, true, targetOs);
                printRow("mean", meanRow);
                printRow("stddev", stddevRow);
                csvRows.add(csvRow(scenario.label(), scale, "mean", meanRow));
                csvRows.add(csvRow(scenario.label(), scale, "stddev", stddevRow));
            }
        }

        writeCsv(csvRows);
    }

    private static void runMemWorker(List<String> args) {
        Scenario scenario = Scenario.fromLabel(args.get(0));
        long count = Long.parseLong(args.get(1));
        long seed = Long.parseLong(args.get(2));
        System.out.println(serializeMemResult(runMemory(scenario, count, seed)));
    }

    private static void guardJitEnabled() {
        if (ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-Xint")) {
            System.err.println("bench must run with the JIT enabled (remove -Xint)");
            System.exit(1);
        }
    }

    private static void writeCsv(List<String> csvRows) {
        try {
            Path dir = Path.of("build", "bench-results");
            Files.createDirectories(dir);
            Path csv = dir.resolve(Instant.now().getEpochSecond() + ".csv");
            Files.writeString(csv, String.join("\n", csvRows));
            System.out.println("\nresults written to " + csv.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("failed to write CSV result file", e);
        }
    }

    // --- 헬퍼 ---

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    private static String normalizedOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        if (os.contains("win")) {
            return "windows";
        }
        return os;
    }

    private static OptionalDouble optLongToDouble(OptionalLong v) {
        return v.isPresent() ? OptionalDouble.of(v.getAsLong()) : OptionalDouble.empty();
    }

    private static String optLongStr(OptionalLong v) {
        return v.isPresent() ? Long.toString(v.getAsLong()) : "";
    }

    private static OptionalLong parseOptLong(String s) {
        return s.isEmpty() ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(s));
    }

    private static String csvOptional(OptionalDouble v) {
        return v.isPresent() ? num(v.getAsDouble()) : "";
    }

    private static String displayOptional(OptionalDouble v) {
        return v.isPresent() ? num(v.getAsDouble()) : "NA";
    }

    /** 정수면 소수점 없이, 아니면 지수표기 없이 출력(Rust f64 Display에 가깝게). */
    static String num(double v) {
        if (!Double.isFinite(v)) {
            return Double.toString(v);
        }
        if (v == Math.rint(v) && Math.abs(v) < 9.0e15) {
            return Long.toString((long) v);
        }
        return BigDecimal.valueOf(v).toPlainString();
    }
}
