package dev.junyoung.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

/**
 * 프로세스 RSS(KB) 측정. rust-engine 하니스 {@code mem.rs}를 포팅한다.
 *
 * <p>Linux에서만 {@code /proc/self/status}의 {@code VmRSS}를 읽고, 그 외 OS에서는 비어 있는 값을
 * 반환한다(Rust {@code read_rss_kb}와 동일 의미). JVM의 RSS는 힙 예약·GC가 섞여 의미가 약하므로
 * Rust 컬럼 parity용 best-effort 신호다. Java 메모리의 주신호는 {@link JvmDiagnostics#heapUsedKb()}.
 */
public final class Rss {
    private static final boolean LINUX =
            System.getProperty("os.name", "").toLowerCase().contains("linux");

    private Rss() {}

    public static OptionalLong readRssKb() {
        if (!LINUX) {
            return OptionalLong.empty();
        }
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.substring("VmRSS:".length()).trim().split("\\s+");
                    return OptionalLong.of(Long.parseLong(parts[0]));
                }
            }
            return OptionalLong.empty();
        } catch (IOException | NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return OptionalLong.empty();
        }
    }

    public static boolean isSupported() {
        return readRssKb().isPresent();
    }
}
