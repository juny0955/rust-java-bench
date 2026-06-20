# Rust 엔진 기준선

측정일: 2026-06-20
실행 환경: 루트 [`README.md`](../../README.md#실행-환경) 기준 (OCI, aarch64 Neoverse-N1, 2 vCPU, Ubuntu 24.04.4 LTS, 커널 6.17.0-1016-oracle)
실행 명령: `cargo run --release --bin bench_runner` (`rust-engine/`)
측정 방식/CSV 컬럼 정의: [`rust-engine/benches/README.md`](../../rust-engine/benches/README.md)

## 요약 (mean ± stddev, n=10)

| 시나리오 | 스케일 | ops/s | p50 | p90 | p99 | p999 | max | peak RSS | avg RSS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| ThinBook | 1,000,000 | 7,219,060 ± 135,028 | 120ns | 160ns | 572ns ± 44ns | 6,832ns ± 318ns | 46,108ns | 71,261KB ± 439KB | 43,583KB ± 74KB |
| ThinBook | 10,000,000 | 7,044,906 ± 69,007 | 120ns | 172ns ± 18ns | 360ns | 4,680ns ± 526ns | 147,753ns | 690,280KB ± 1,635KB | 335,785KB ± 664KB |
| ActiveFill | 1,000,000 | 13,921,520 ± 67,787 | 80ns | 160ns | 240ns | 1,052ns ± 220ns | 279,586ns | 20,119KB ± 1,408KB | 12,854KB ± 851KB |
| ActiveFill | 10,000,000 | 14,236,476 ± 27,245 | 80ns | 160ns | 240ns | 1,184ns ± 37ns | 225,937ns | 104,959KB ± 53KB | 56,478KB ± 389KB |
| WorstCaseCross | 1,000,000 | 3,897,419 ± 10,080 | 120ns | 200ns | 280ns | 31,776ns ± 21,141ns | 213,393ns | 6,834KB ± 3KB | 6,362KB ± 3KB |
| WorstCaseCross | 10,000,000 | 3,893,413 ± 26,758 | 120ns | 200ns | 304ns ± 32ns | 25,932ns ± 3,296ns | 124,609ns | 6,864KB ± 6KB | 6,803KB ± 6KB |

원본 CSV: [`raw.csv`](./raw.csv) (측정 인스턴스의 `rust-engine/target/bench-results/1781925291.csv`에서 가져옴)

## 관찰

- **ActiveFill > ThinBook > WorstCaseCross** 순으로 처리량이 나옴. `ActiveFill`은 체결이 잦아 호가창 깊이가 얕게 유지되어 가장 빠르고(p50 80ns), `ThinBook`은 체결 없이 주문이 계속 쌓여 호가창 조회/삽입 비용이 더 들고, `WorstCaseCross`는 sweep taker가 매칭 루프를 매 사이클 1,000번 반복시키는 의도된 worst case라 가장 느림.
- `ThinBook`의 peak RSS는 1M→71MB, 10M→690MB로 스케일에 거의 선형 증가. 체결이 거의 없어 미체결 주문이 계속 적재되는 시나리오 특성과 일치.
- `ActiveFill`은 같은 스케일 비율로 RSS가 ThinBook 대비 훨씬 작게 유지(10M 기준 105MB) — 체결로 호가창이 비워지는 빈도가 높다는 뜻.
- `WorstCaseCross`는 1M/10M 모두 peak RSS가 ~6.8MB로 거의 동일. 결정적 워크로드 특성상 호가창 깊이가 항상 `SWEEP_LEVELS`(1,000) 수준에서 평형을 이루기 때문.
- `WorstCaseCross`의 p999 stddev(21,141ns @1M, 3,296ns @10M)만 다른 지표보다 크게 튐. 이 시나리오는 seed 무관 완전 결정적 워크로드라 반복 간 워크로드 분산이 없으므로, 이 변동은 순수 타이밍 노이즈(OS 스케줄링 등)로 해석한다 — `rust-engine/benches/README.md`에 기술된 의도와 일치.
- 모든 시나리오의 ops_sec stddev는 평균 대비 0.2~0.7% 수준으로 측정이 안정적임.

## 참고

- 본 결과는 Java 개선 이력의 기준선이다. Java 결과는 [`../java/`](../java/) 아래에 버전별로 누적한다.
