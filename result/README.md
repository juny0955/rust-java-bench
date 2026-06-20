# 벤치마크 결과 추적

Rust를 기준선으로 고정하고, Java 구현을 개선하면서 Rust 처리량에 얼마나 근접하는지 추적한다.

## 기준선

| 엔진 | 결과 | 원본 CSV | 역할 |
| --- | --- | --- | --- |
| Rust | [`rust-baseline`](./rust-baseline/) | [`raw.csv`](./rust-baseline/raw.csv) | 비교 기준선 |

## Java 개선 이력

| 버전 | 결과 | 원본 CSV | 요약 |
| --- | --- | --- | --- |
| v0-initial | [`java/v0-initial`](./java/v0-initial/) | [`raw.csv`](./java/v0-initial/raw.csv) | Java 초기 포팅 결과 |
| v1-adaptive-warmup | [`java/v1-adaptive-warmup`](./java/v1-adaptive-warmup/) | [`raw.csv`](./java/v1-adaptive-warmup/raw.csv) | 적응형 warmup으로 JIT 안정화 후 측정 |
| v1.1-scenario-update | [`java/v1.1-scenario-update`](./java/v1.1-scenario-update/) | [`raw.csv`](./java/v1.1-scenario-update/raw.csv) | 시나리오 분리(`DeepSweepCross`/`BookGrowthWorst`) 후 재측정, 워밍업/엔진 변경 없음 |

## Rust 기준 Java 근접도

`Java/Rust`는 동일 환경, 동일 워크로드에서 `mean ops/s`를 기준으로 계산한다. `DeepSweepCross`는
구 `WorstCaseCross`의 새 이름이며(v0/v1 측정 당시 라벨), `BookGrowthWorst`는 v1.1에서 처음
추가된 시나리오라 이전 버전에는 측정값이 없다(`—`).

| Java 버전 | ThinBook 1M | ThinBook 10M | ActiveFill 1M | ActiveFill 10M | DeepSweepCross 1M | DeepSweepCross 10M | BookGrowthWorst 1M | BookGrowthWorst 10M |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| v0-initial | 57% | 49% | 85% | 69% | 86% | 112% | — | — |
| v1-adaptive-warmup | 61% | 47% | 82% | 73% | 103% | 109% | — | — |
| v1.1-scenario-update | 63% | 49% | 72% | 70% | 112% | 114% | 67% | 39% |

## 해석 기준

- Rust 결과는 기준선으로 유지하고, Java 개선은 `result/java/{version}/` 아래에 새 디렉터리로 추가한다.
- 각 Java 버전 문서는 Rust 기준선 대비 처리량 비율, RSS 배수, JVM 진단을 함께 기록한다.
- Java가 Rust를 초과하는 시나리오도 그대로 기록한다. 목표는 단순 승패가 아니라 어떤 워크로드에서 어떤 비용이 격차를 만드는지 보는 것이다.
