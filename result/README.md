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

## Rust 기준 Java 근접도

`Java/Rust`는 동일 환경, 동일 워크로드에서 `mean ops/s`를 기준으로 계산한다.

| Java 버전 | ThinBook 1M | ThinBook 10M | ActiveFill 1M | ActiveFill 10M | WorstCaseCross 1M | WorstCaseCross 10M |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| v0-initial | 57% | 49% | 85% | 69% | 86% | 112% |

## 해석 기준

- Rust 결과는 기준선으로 유지하고, Java 개선은 `result/java/{version}/` 아래에 새 디렉터리로 추가한다.
- 각 Java 버전 문서는 Rust 기준선 대비 처리량 비율, RSS 배수, JVM 진단을 함께 기록한다.
- Java가 Rust를 초과하는 시나리오도 그대로 기록한다. 목표는 단순 승패가 아니라 어떤 워크로드에서 어떤 비용이 격차를 만드는지 보는 것이다.
