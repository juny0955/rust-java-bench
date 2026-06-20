package dev.junyoung.bench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BenchRunnerArgsTest {

    @Test
    @DisplayName("parseArgValue: prefix가 매칭되면 값을, 없으면 null을 반환한다")
    void parseArgValueFindsLastMatch() {
        assertEquals("ThinBook",
                BenchRunner.parseArgValue(List.of("--scenario=ThinBook"), "--scenario="));
        assertNull(BenchRunner.parseArgValue(List.of(), "--scenario="));
        assertNull(BenchRunner.parseArgValue(List.of("--other=foo"), "--scenario="));
        assertEquals("B",
                BenchRunner.parseArgValue(List.of("--scenario=A", "--scenario=B"), "--scenario="));
    }

    @Test
    @DisplayName("selectScenarios(null)은 전체 시나리오 4개를 반환한다")
    void selectScenariosNullReturnsAll() {
        Scenario[] scenarios = BenchRunner.selectScenarios(null);
        assertArrayEquals(
                new Scenario[] {
                    Scenario.THIN_BOOK, Scenario.ACTIVE_FILL,
                    Scenario.DEEP_SWEEP_CROSS, Scenario.BOOK_GROWTH_WORST
                },
                scenarios);
    }

    @Test
    @DisplayName("selectScenarios(label)은 해당 시나리오 하나만 반환한다")
    void selectScenariosWithLabelReturnsSingle() {
        assertArrayEquals(new Scenario[] {Scenario.THIN_BOOK}, BenchRunner.selectScenarios("ThinBook"));
    }

    @Test
    @DisplayName("selectScenarios(잘못된 label)은 유효 라벨 목록을 포함한 예외를 던진다")
    void selectScenariosWithUnknownLabelThrows() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> BenchRunner.selectScenarios("Bogus"));
        for (Scenario s : Scenario.values()) {
            assertTrue(e.getMessage().contains(s.label()), "메시지에 " + s.label() + " 포함되어야 함");
        }
    }

    @Test
    @DisplayName("selectScales(null)은 전체 스케일 2개를 반환한다")
    void selectScalesNullReturnsAll() {
        assertArrayEquals(new long[] {1_000_000L, 10_000_000L}, BenchRunner.selectScales(null));
    }

    @Test
    @DisplayName("selectScales(count)는 해당 스케일 하나만 반환한다")
    void selectScalesWithValueReturnsSingle() {
        assertArrayEquals(new long[] {1_000_000L}, BenchRunner.selectScales("1000000"));
    }

    @Test
    @DisplayName("selectScales는 숫자가 아니거나 0 이하인 값에 예외를 던진다")
    void selectScalesRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> BenchRunner.selectScales("abc"));
        assertThrows(IllegalArgumentException.class, () -> BenchRunner.selectScales("0"));
        assertThrows(IllegalArgumentException.class, () -> BenchRunner.selectScales("-5"));
    }
}
