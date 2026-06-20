package dev.junyoung.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AggregateTest {

    @Test
    @DisplayName("모표준편차(분모 n)로 mean/stddev를 계산한다")
    void populationStddev() {
        // 고전 예제: mean=5, 모분산=4, 모표준편차=2 (표본표준편차 아님)
        double[] ms = BenchRunner.meanStddev(new double[] {2, 4, 4, 4, 5, 5, 7, 9});
        assertEquals(5.0, ms[0], 1e-12);
        assertEquals(2.0, ms[1], 1e-12);
    }

    @Test
    @DisplayName("동일 값들의 표준편차는 0이다")
    void zeroStddevForConstant() {
        double[] ms = BenchRunner.meanStddev(new double[] {3, 3, 3, 3});
        assertEquals(3.0, ms[0], 1e-12);
        assertEquals(0.0, ms[1], 1e-12);
    }

    @Test
    @DisplayName("정수는 소수점 없이, 실수는 지수표기 없이 포맷한다")
    void numberFormatting() {
        assertEquals("1", BenchRunner.num(1.0));
        assertEquals("0", BenchRunner.num(0.0));
        assertEquals("1.5", BenchRunner.num(1.5));
        assertEquals("12345678.5", BenchRunner.num(12345678.5));
    }
}
