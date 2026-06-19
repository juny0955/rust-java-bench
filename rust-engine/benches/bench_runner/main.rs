mod histogram;
mod mem;
mod workload;

use histogram::LatencyRecorder;
use rust_engine::MatchingEngine;
use std::fs;
use std::io::Write;
use std::path::Path;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use workload::{Scenario, WorkloadGenerator};

const WARMUP_MIN: u64 = 10_000;
const WARMUP_DIVISOR: u64 = 10;
const REPS: usize = 10;
const BATCH_SIZE: usize = 100_000;
const TARGET_LATENCY_SAMPLES: u64 = 100_000;
const SCALES: [u64; 2] = [1_000_000, 10_000_000];
const SCENARIOS: [Scenario; 3] = [
    Scenario::ThinBook,
    Scenario::ActiveFill,
    Scenario::WorstCaseCross,
];

struct RunResult {
    ops_sec: f64,
    submit_elapsed_ms: f64,
    p50_ns: u64,
    p90_ns: u64,
    p99_ns: u64,
    p999_ns: u64,
    max_ns: u64,
    latency_sample_count: usize,
    latency_sample_stride: u64,
    baseline_rss_kb: Option<u64>,
    peak_rss_kb: Option<u64>,
    avg_rss_kb: Option<u64>,
    rss_supported: bool,
    target_os: &'static str,
}

struct MetricRow {
    ops_sec: f64,
    submit_elapsed_ms: f64,
    p50_ns: f64,
    p90_ns: f64,
    p99_ns: f64,
    p999_ns: f64,
    max_ns: f64,
    latency_sample_count: f64,
    latency_sample_stride: f64,
    baseline_rss_kb: Option<f64>,
    peak_rss_kb: Option<f64>,
    avg_rss_kb: Option<f64>,
    rss_supported: bool,
    target_os: &'static str,
}

impl From<&RunResult> for MetricRow {
    fn from(r: &RunResult) -> Self {
        Self {
            ops_sec: r.ops_sec,
            submit_elapsed_ms: r.submit_elapsed_ms,
            p50_ns: r.p50_ns as f64,
            p90_ns: r.p90_ns as f64,
            p99_ns: r.p99_ns as f64,
            p999_ns: r.p999_ns as f64,
            max_ns: r.max_ns as f64,
            latency_sample_count: r.latency_sample_count as f64,
            latency_sample_stride: r.latency_sample_stride as f64,
            baseline_rss_kb: r.baseline_rss_kb.map(|kb| kb as f64),
            peak_rss_kb: r.peak_rss_kb.map(|kb| kb as f64),
            avg_rss_kb: r.avg_rss_kb.map(|kb| kb as f64),
            rss_supported: r.rss_supported,
            target_os: r.target_os,
        }
    }
}

fn aggregate(runs: &[RunResult], pick: impl Fn(&MetricRow) -> f64) -> (f64, f64) {
    let values: Vec<f64> = runs.iter().map(|r| pick(&MetricRow::from(r))).collect();
    mean_stddev(&values)
}

fn aggregate_optional(
    runs: &[RunResult],
    pick: impl Fn(&MetricRow) -> Option<f64>,
) -> (Option<f64>, Option<f64>) {
    let values: Vec<f64> = runs
        .iter()
        .filter_map(|r| pick(&MetricRow::from(r)))
        .collect();
    if values.is_empty() {
        (None, None)
    } else {
        let (mean, stddev) = mean_stddev(&values);
        (Some(mean), Some(stddev))
    }
}

fn mean_stddev(values: &[f64]) -> (f64, f64) {
    assert!(
        !values.is_empty(),
        "cannot aggregate empty benchmark results"
    );
    let n = values.len() as f64;
    let mean = values.iter().sum::<f64>() / n;
    let variance = values.iter().map(|v| (v - mean).powi(2)).sum::<f64>() / n;
    (mean, variance.sqrt())
}

fn print_row(label: &str, m: &MetricRow) {
    println!(
        "  {label:7}: ops_sec={:.1} submit_ms={:.1} p50={:.0} p90={:.0} p99={:.0} p999={:.0} max={:.0} samples={:.0} stride={:.0} baseline_rss_kb={} peak_rss_kb={} avg_rss_kb={}",
        m.ops_sec,
        m.submit_elapsed_ms,
        m.p50_ns,
        m.p90_ns,
        m.p99_ns,
        m.p999_ns,
        m.max_ns,
        m.latency_sample_count,
        m.latency_sample_stride,
        display_optional(m.baseline_rss_kb),
        display_optional(m.peak_rss_kb),
        display_optional(m.avg_rss_kb)
    );
}

fn csv_row(scenario: &str, scale: u64, row_type: &str, m: &MetricRow) -> String {
    format!(
        "{scenario},{scale},{row_type},{},{},{},{},{},{},{},{},{},{},{},{},{},{}",
        m.ops_sec,
        m.submit_elapsed_ms,
        m.p50_ns,
        m.p90_ns,
        m.p99_ns,
        m.p999_ns,
        m.max_ns,
        m.latency_sample_count,
        m.latency_sample_stride,
        csv_optional(m.baseline_rss_kb),
        csv_optional(m.peak_rss_kb),
        csv_optional(m.avg_rss_kb),
        m.rss_supported,
        m.target_os
    )
}

fn csv_header() -> &'static str {
    "scenario,scale,row_type,ops_sec,submit_elapsed_ms,p50_ns,p90_ns,p99_ns,p999_ns,max_ns,latency_sample_count,latency_sample_stride,baseline_rss_kb,peak_rss_kb,avg_rss_kb,rss_supported,target_os"
}

fn csv_optional(value: Option<f64>) -> String {
    value.map(|v| v.to_string()).unwrap_or_default()
}

fn display_optional(value: Option<f64>) -> String {
    value
        .map(|v| format!("{v:.0}"))
        .unwrap_or_else(|| "NA".to_string())
}

fn scenario_name(s: Scenario) -> &'static str {
    match s {
        Scenario::ThinBook => "ThinBook",
        Scenario::ActiveFill => "ActiveFill",
        Scenario::WorstCaseCross => "WorstCaseCross",
    }
}

fn warmup_count_for(scale: u64) -> u64 {
    (scale / WARMUP_DIVISOR).max(WARMUP_MIN)
}

/// Measures the per-sample cost of the latency timing pattern itself
/// (`Instant::now()` + `Instant::elapsed()`), so the reported latency
/// percentiles can be read with this fixed overhead in mind.
fn measure_timer_overhead_ns() -> f64 {
    const ITERS: u64 = 2_000_000;
    let start = Instant::now();
    for _ in 0..ITERS {
        let t0 = Instant::now();
        std::hint::black_box(t0.elapsed());
    }
    start.elapsed().as_nanos() as f64 / ITERS as f64
}

fn run_warmup(scenario: Scenario, warmup_count: u64) {
    let mut engine = MatchingEngine::new();
    let mut generator = WorkloadGenerator::new(scenario, 0xDEAD_BEEF, warmup_count);
    while !generator.is_exhausted() {
        for order in generator.next_batch(BATCH_SIZE) {
            let _ = engine.submit_limit_order(order);
        }
    }
}

fn run_throughput(scenario: Scenario, count: u64, seed: u64) -> Duration {
    let mut engine = MatchingEngine::new();
    let mut generator = WorkloadGenerator::new(scenario, seed, count);
    let mut submit_elapsed = Duration::ZERO;
    let mut submitted = 0u64;

    while !generator.is_exhausted() {
        let batch = generator.next_batch(BATCH_SIZE);
        submitted += batch.len() as u64;
        let submit_start = Instant::now();
        for order in batch {
            let _ = engine.submit_limit_order(order);
        }
        submit_elapsed += submit_start.elapsed();
    }

    assert_eq!(submitted, count);
    assert!(submit_elapsed.as_nanos() > 0);
    submit_elapsed
}

fn run_latency(
    scenario: Scenario,
    count: u64,
    seed: u64,
    sample_stride: u64,
) -> histogram::LatencySummary {
    let mut engine = MatchingEngine::new();
    let mut generator = WorkloadGenerator::new(scenario, seed, count);
    let sample_capacity = count.div_ceil(sample_stride) as usize;
    let mut recorder = LatencyRecorder::with_capacity(sample_capacity);
    let mut seen = 0u64;

    while !generator.is_exhausted() {
        for order in generator.next_batch(BATCH_SIZE) {
            if seen.is_multiple_of(sample_stride) {
                let t0 = Instant::now();
                let _ = engine.submit_limit_order(order);
                recorder.record(t0.elapsed().as_nanos() as u64);
            } else {
                let _ = engine.submit_limit_order(order);
            }
            seen += 1;
        }
    }

    assert_eq!(seen, count);
    let summary = recorder.summary();
    assert_eq!(summary.count, sample_capacity);
    assert!(summary.count > 0);
    assert!(recorder.sum_ns() > 0);
    summary
}

fn run_memory(
    scenario: Scenario,
    count: u64,
    seed: u64,
) -> (Option<u64>, Option<u64>, Option<u64>, bool) {
    let mut engine = MatchingEngine::new();
    let mut generator = WorkloadGenerator::new(scenario, seed, count);
    let rss_supported = mem::read_rss_kb().is_some();
    // Baseline is the empty-engine RSS, kept separate so it does not skew
    // the loaded-state average. Loaded samples are taken after each batch.
    let baseline_rss_kb = mem::read_rss_kb();
    let mut rss_samples: Vec<u64> = Vec::new();

    while !generator.is_exhausted() {
        for order in generator.next_batch(BATCH_SIZE) {
            let _ = engine.submit_limit_order(order);
        }
        if let Some(kb) = mem::read_rss_kb() {
            rss_samples.push(kb);
        }
    }

    let peak_rss_kb = rss_samples.iter().copied().max();
    let avg_rss_kb = if rss_samples.is_empty() {
        None
    } else {
        Some(rss_samples.iter().sum::<u64>() / rss_samples.len() as u64)
    };
    (baseline_rss_kb, peak_rss_kb, avg_rss_kb, rss_supported)
}

fn run_once(scenario: Scenario, count: u64, seed: u64) -> RunResult {
    let latency_sample_stride = (count / TARGET_LATENCY_SAMPLES).max(1);
    let submit_elapsed = run_throughput(scenario, count, seed);
    let summary = run_latency(scenario, count, seed, latency_sample_stride);
    let (baseline_rss_kb, peak_rss_kb, avg_rss_kb, rss_supported) =
        run_memory(scenario, count, seed);

    RunResult {
        ops_sec: count as f64 / submit_elapsed.as_secs_f64(),
        submit_elapsed_ms: submit_elapsed.as_secs_f64() * 1000.0,
        p50_ns: summary.p50,
        p90_ns: summary.p90,
        p99_ns: summary.p99,
        p999_ns: summary.p999,
        max_ns: summary.max,
        latency_sample_count: summary.count,
        latency_sample_stride,
        baseline_rss_kb,
        peak_rss_kb,
        avg_rss_kb,
        rss_supported,
        target_os: std::env::consts::OS,
    }
}

fn main() {
    if cfg!(debug_assertions) {
        eprintln!("bench_runner must be run with --release");
        std::process::exit(1);
    }

    let timer_overhead_ns = measure_timer_overhead_ns();
    println!(
        "timer_overhead_ns (Instant::now()+elapsed per latency sample): {timer_overhead_ns:.1}"
    );
    println!(
        "latency percentiles include this per-sample timer overhead; subtract it for an engine-only estimate.\n"
    );

    let mut csv_rows: Vec<String> = vec![csv_header().to_string()];

    for &scenario in SCENARIOS.iter() {
        for &scale in SCALES.iter() {
            println!("== {} / {} ==", scenario_name(scenario), scale);
            run_warmup(scenario, warmup_count_for(scale));

            let mut runs: Vec<RunResult> = Vec::with_capacity(REPS);
            for rep in 0..REPS {
                let seed = 0x1234_5678_9ABC_DEF0u64.wrapping_add(rep as u64);
                let result = run_once(scenario, scale, seed);
                let row = MetricRow::from(&result);
                print_row(&format!("run_{rep}"), &row);
                csv_rows.push(csv_row(
                    scenario_name(scenario),
                    scale,
                    &format!("run_{rep}"),
                    &row,
                ));
                runs.push(result);
            }

            let ops_sec = aggregate(&runs, |m| m.ops_sec);
            let submit_elapsed_ms = aggregate(&runs, |m| m.submit_elapsed_ms);
            let p50_ns = aggregate(&runs, |m| m.p50_ns);
            let p90_ns = aggregate(&runs, |m| m.p90_ns);
            let p99_ns = aggregate(&runs, |m| m.p99_ns);
            let p999_ns = aggregate(&runs, |m| m.p999_ns);
            let max_ns = aggregate(&runs, |m| m.max_ns);
            let latency_sample_count = aggregate(&runs, |m| m.latency_sample_count);
            let latency_sample_stride = aggregate(&runs, |m| m.latency_sample_stride);
            let baseline_rss_kb = aggregate_optional(&runs, |m| m.baseline_rss_kb);
            let peak_rss_kb = aggregate_optional(&runs, |m| m.peak_rss_kb);
            let avg_rss_kb = aggregate_optional(&runs, |m| m.avg_rss_kb);
            let rss_supported = runs.iter().any(|r| r.rss_supported);

            let mean_row = MetricRow {
                ops_sec: ops_sec.0,
                submit_elapsed_ms: submit_elapsed_ms.0,
                p50_ns: p50_ns.0,
                p90_ns: p90_ns.0,
                p99_ns: p99_ns.0,
                p999_ns: p999_ns.0,
                max_ns: max_ns.0,
                latency_sample_count: latency_sample_count.0,
                latency_sample_stride: latency_sample_stride.0,
                baseline_rss_kb: baseline_rss_kb.0,
                peak_rss_kb: peak_rss_kb.0,
                avg_rss_kb: avg_rss_kb.0,
                rss_supported,
                target_os: std::env::consts::OS,
            };
            let stddev_row = MetricRow {
                ops_sec: ops_sec.1,
                submit_elapsed_ms: submit_elapsed_ms.1,
                p50_ns: p50_ns.1,
                p90_ns: p90_ns.1,
                p99_ns: p99_ns.1,
                p999_ns: p999_ns.1,
                max_ns: max_ns.1,
                latency_sample_count: latency_sample_count.1,
                latency_sample_stride: latency_sample_stride.1,
                baseline_rss_kb: baseline_rss_kb.1,
                peak_rss_kb: peak_rss_kb.1,
                avg_rss_kb: avg_rss_kb.1,
                rss_supported,
                target_os: std::env::consts::OS,
            };

            print_row("mean", &mean_row);
            print_row("stddev", &stddev_row);
            csv_rows.push(csv_row(scenario_name(scenario), scale, "mean", &mean_row));
            csv_rows.push(csv_row(
                scenario_name(scenario),
                scale,
                "stddev",
                &stddev_row,
            ));
        }
    }

    let results_dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("target/bench-results");
    fs::create_dir_all(&results_dir).expect("failed to create target/bench-results directory");
    let ts = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system clock should be after UNIX_EPOCH")
        .as_secs();
    let csv_path = results_dir.join(format!("{ts}.csv"));
    let mut file = fs::File::create(&csv_path).expect("failed to create CSV result file");
    file.write_all(csv_rows.join("\n").as_bytes())
        .expect("failed to write CSV result file");
    println!("\nresults written to {}", csv_path.display());
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unsupported_rss_fields_are_empty_in_csv() {
        let row = MetricRow {
            ops_sec: 1.0,
            submit_elapsed_ms: 2.0,
            p50_ns: 3.0,
            p90_ns: 4.0,
            p99_ns: 5.0,
            p999_ns: 6.0,
            max_ns: 7.0,
            latency_sample_count: 8.0,
            latency_sample_stride: 9.0,
            baseline_rss_kb: None,
            peak_rss_kb: None,
            avg_rss_kb: None,
            rss_supported: false,
            target_os: "windows",
        };

        let csv = csv_row("Scenario", 10, "run_0", &row);

        assert_eq!(csv, "Scenario,10,run_0,1,2,3,4,5,6,7,8,9,,,,false,windows");
    }

    #[test]
    fn csv_header_includes_measurement_metadata() {
        assert!(csv_header().contains("latency_sample_count"));
        assert!(csv_header().contains("baseline_rss_kb"));
        assert!(csv_header().contains("rss_supported"));
        assert!(csv_header().contains("target_os"));
    }
}
