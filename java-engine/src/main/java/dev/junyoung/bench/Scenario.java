package dev.junyoung.bench;

/**
 * 벤치마크 워크로드 시나리오. rust-engine 하니스의 {@code workload.rs} {@code Scenario}와 동일하다.
 *
 * <p>{@link #label()}는 CSV/stdout에 쓰이는 표기로, Rust {@code scenario_name}과 문자 단위로 같다.
 */
public enum Scenario {
    THIN_BOOK("ThinBook"),
    ACTIVE_FILL("ActiveFill"),
    WORST_CASE_CROSS("WorstCaseCross");

    private final String label;

    Scenario(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Rust {@code parse_scenario}에 대응. 표기 문자열로 시나리오를 되돌린다. */
    public static Scenario fromLabel(String label) {
        for (Scenario s : values()) {
            if (s.label.equals(label)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown scenario: " + label);
    }
}
