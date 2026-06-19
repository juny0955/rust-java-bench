pub struct LatencySummary {
    pub p50: u64,
    pub p90: u64,
    pub p99: u64,
    pub p999: u64,
    pub max: u64,
    pub count: usize,
}

pub struct LatencyRecorder {
    samples: Vec<u64>,
    sum_ns: u64,
}

impl LatencyRecorder {
    pub fn with_capacity(count: usize) -> Self {
        Self {
            samples: Vec::with_capacity(count),
            sum_ns: 0,
        }
    }

    pub fn record(&mut self, ns: u64) {
        self.samples.push(ns);
        self.sum_ns += ns;
    }

    pub fn sum_ns(&self) -> u64 {
        self.sum_ns
    }

    fn percentile_of(sorted: &[u64], p: f64) -> u64 {
        if sorted.is_empty() {
            return 0;
        }
        let idx = ((sorted.len() as f64 - 1.0) * p).round() as usize;
        sorted[idx.min(sorted.len() - 1)]
    }

    pub fn summary(&self) -> LatencySummary {
        let mut sorted = self.samples.clone();
        sorted.sort_unstable();
        LatencySummary {
            p50: Self::percentile_of(&sorted, 0.50),
            p90: Self::percentile_of(&sorted, 0.90),
            p99: Self::percentile_of(&sorted, 0.99),
            p999: Self::percentile_of(&sorted, 0.999),
            max: sorted.last().copied().unwrap_or(0),
            count: sorted.len(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn with_capacity_preallocates_samples() {
        let mut recorder = LatencyRecorder::with_capacity(100);
        assert_eq!(recorder.samples.capacity(), 100);
        for i in 0..100u64 {
            recorder.record(i);
        }
        assert_eq!(recorder.samples.capacity(), 100);
        assert_eq!(recorder.sum_ns(), (0..100u64).sum::<u64>());
    }

    #[test]
    fn summary_returns_ordered_percentiles() {
        let mut recorder = LatencyRecorder::with_capacity(10);
        for v in [10, 1, 5, 9, 2, 8, 3, 7, 4, 6] {
            recorder.record(v);
        }
        let summary = recorder.summary();
        assert_eq!(summary.count, 10);
        assert_eq!(summary.max, 10);
        assert!(summary.p50 <= summary.p90);
        assert!(summary.p90 <= summary.p99);
        assert!(summary.p99 <= summary.p999);
        assert!(summary.p999 <= summary.max);
    }
}
