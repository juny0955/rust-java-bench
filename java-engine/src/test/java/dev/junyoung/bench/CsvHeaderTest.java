package dev.junyoung.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.junyoung.bench.BenchRunner.MetricRow;
import java.util.OptionalDouble;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CsvHeaderTest {

    /** rust-engine main.rs csv_header()와 문자 단위로 동일해야 하는 앞 17개 컬럼. */
    private static final String RUST_HEADER =
            "scenario,scale,row_type,ops_sec,submit_elapsed_ms,p50_ns,p90_ns,p99_ns,p999_ns,max_ns,"
            + "latency_sample_count,latency_sample_stride,baseline_rss_kb,peak_rss_kb,avg_rss_kb,"
            + "rss_supported,target_os";

    @Test
    @DisplayName("CSV 헤더의 앞 17컬럼이 Rust와 byte 동일하고, 진단 컬럼은 target_os 뒤에 온다")
    void headerKeepsRustPrefixAndAppendsDiagnostics() {
        String header = BenchRunner.csvHeader();
        assertTrue(header.startsWith(RUST_HEADER), "Rust 17컬럼 prefix 유지");
        assertEquals(
                ",gc_count,gc_time_ms,jit_compile_ms,alloc_bytes,heap_used_peak_kb,heap_used_avg_kb",
                header.substring(RUST_HEADER.length()));
    }

    @Test
    @DisplayName("미지원 RSS/alloc 컬럼은 빈 값으로 기록된다")
    void unsupportedOptionalColumnsAreEmpty() {
        MetricRow row = new MetricRow(
                1.0, 2.0, 3, 4, 5, 6, 7, 8, 9,
                OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty(),
                false, "macos",
                10, 11, 12, OptionalDouble.empty(), 13, 14);
        String csv = BenchRunner.csvRow("ThinBook", 1000, "run_0", row);
        assertEquals(
                "ThinBook,1000,run_0,1,2,3,4,5,6,7,8,9,,,,false,macos,10,11,12,,13,14", csv);
    }
}
