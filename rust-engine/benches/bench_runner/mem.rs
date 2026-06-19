#[cfg(target_os = "linux")]
pub fn read_rss_kb() -> Option<u64> {
    let status = std::fs::read_to_string("/proc/self/status").ok()?;
    for line in status.lines() {
        if let Some(rest) = line.strip_prefix("VmRSS:") {
            return rest
                .split_whitespace()
                .next()
                .and_then(|n| n.parse::<u64>().ok());
        }
    }
    None
}

#[cfg(not(target_os = "linux"))]
pub fn read_rss_kb() -> Option<u64> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(target_os = "linux")]
    #[test]
    fn linux_returns_some_positive_rss() {
        assert!(read_rss_kb().is_some_and(|kb| kb > 0));
    }

    #[cfg(not(target_os = "linux"))]
    #[test]
    fn unsupported_platform_returns_none() {
        assert_eq!(read_rss_kb(), None);
    }
}
