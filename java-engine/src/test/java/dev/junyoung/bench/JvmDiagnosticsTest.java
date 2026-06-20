package dev.junyoung.bench;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.junyoung.bench.JvmDiagnostics.Snapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JvmDiagnosticsTest {

    @Test
    @DisplayName("snapshot delta는 비음수이고 heapUsedKb는 양수다")
    void snapshotDeltasAreNonNegativeAndHeapPositive() {
        JvmDiagnostics diag = new JvmDiagnostics();
        Snapshot start = diag.snapshot();

        // 약간의 할당을 유발해 delta가 음수가 아님을 확인한다.
        long acc = 0;
        for (int i = 0; i < 200_000; i++) {
            acc += Long.valueOf(i).hashCode();
        }
        if (acc == Long.MIN_VALUE) {
            throw new AssertionError("unreachable, prevents DCE");
        }

        Snapshot end = diag.snapshot();

        assertTrue(end.gcCount() - start.gcCount() >= 0, "gcCount delta");
        assertTrue(end.gcTimeMs() - start.gcTimeMs() >= 0, "gcTime delta");
        assertTrue(end.jitCompileMs() - start.jitCompileMs() >= 0, "jit delta");
        assertTrue(diag.heapUsedKb() > 0, "heapUsedKb");

        if (start.allocBytes().isPresent() && end.allocBytes().isPresent()) {
            assertTrue(end.allocBytes().getAsLong() - start.allocBytes().getAsLong() >= 0,
                    "allocBytes delta");
        }
    }
}
