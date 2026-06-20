package dev.junyoung.bench;

import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.List;
import java.util.OptionalLong;

/**
 * JVM 병목·리소스 진단 (Java 전용, Rust엔 없음). 전부 JDK 표준 MXBean으로 측정한다.
 *
 * <p>두 언어 비교에서 "Java가 Rust를 따라가려면 어디를 최적화해야 하나"의 직접 신호를 제공한다:
 * GC 정지(횟수·시간), JIT 컴파일 시간, 할당 압력, 힙 점유. {@link #snapshot()}을 pass 경계에서만
 * 읽으므로 per-order 오버헤드가 없어 처리량 측정을 왜곡하지 않는다.
 */
public final class JvmDiagnostics {
    private final List<GarbageCollectorMXBean> gcBeans;
    private final CompilationMXBean compilation; // null 가능
    private final MemoryMXBean memory;
    private final com.sun.management.ThreadMXBean threadBean; // alloc 미지원 시 null
    private final boolean allocSupported;

    public JvmDiagnostics() {
        this.gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        this.compilation = ManagementFactory.getCompilationMXBean();
        this.memory = ManagementFactory.getMemoryMXBean();
        java.lang.management.ThreadMXBean base = ManagementFactory.getThreadMXBean();
        if (base instanceof com.sun.management.ThreadMXBean sun
                && sun.isThreadAllocatedMemorySupported()) {
            this.threadBean = sun;
            this.allocSupported = true;
        } else {
            this.threadBean = null;
            this.allocSupported = false;
        }
    }

    /** pass 경계에서 읽는 누적 카운터 스냅샷. delta를 내려면 두 시점 snapshot을 뺀다. */
    public record Snapshot(long gcCount, long gcTimeMs, long jitCompileMs, OptionalLong allocBytes) {}

    public Snapshot snapshot() {
        long gcCount = 0;
        long gcTimeMs = 0;
        for (GarbageCollectorMXBean gc : gcBeans) {
            long count = gc.getCollectionCount();
            if (count > 0) {
                gcCount += count;
            }
            long time = gc.getCollectionTime();
            if (time > 0) {
                gcTimeMs += time;
            }
        }
        long jit = (compilation != null && compilation.isCompilationTimeMonitoringSupported())
                ? compilation.getTotalCompilationTime()
                : 0;
        OptionalLong alloc = allocSupported
                ? OptionalLong.of(threadBean.getCurrentThreadAllocatedBytes())
                : OptionalLong.empty();
        return new Snapshot(gcCount, gcTimeMs, jit, alloc);
    }

    /** 현재 힙 사용량(KB). 적재 상태 힙 점유 샘플링에 쓴다. RSS와 무관하게 always-on. */
    public long heapUsedKb() {
        return memory.getHeapMemoryUsage().getUsed() / 1024;
    }
}
