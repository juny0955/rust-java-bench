package dev.junyoung.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RssTest {

    @Test
    @DisplayName("RSS는 Linux에서만 양수 값을 주고 그 외 OS에서는 비어 있다")
    void rssSupportedOnlyOnLinux() {
        boolean linux = System.getProperty("os.name", "").toLowerCase().contains("linux");
        OptionalLong rss = Rss.readRssKb();
        assertEquals(linux, rss.isPresent());
        assertEquals(linux, Rss.isSupported());
        if (linux) {
            assertTrue(rss.getAsLong() > 0);
        }
    }
}
