# RUST-JAVA 벤치마크

Rust와 Java로 동일한 매칭엔진을 구현하고, 처리량/지연시간/메모리 사용량을 비교하는 실험 프로젝트입니다.

## 비교 환경

본 프로젝트는 2026-06-18 기준 사용 가능한 안정 버전을 기준으로 측정합니다.

| 항목   |     버전 | 비고        |
| ---- | -----: | --------- |
| Java |     25 | 최신 LTS 기준 |
| Rust | 1.96.0 | stable 기준 |

> Java의 최신 feature release는 JDK 26이지만, 본 프로젝트에서는 장기 지원 및 실무 적용 가능성을 고려하여 Java 25 LTS를 기준으로 비교합니다.

## 실행 환경

벤치마크는 아래 클라우드 인스턴스에서 측정합니다.

| 항목       | 값                                   |
| -------- | ----------------------------------- |
| 제공자      | Oracle Cloud Infrastructure (OCI)   |
| 아키텍처     | aarch64 (ARM Neoverse-N1)           |
| vCPU     | 2 (NUMA node 1개)                    |
| 메모리      | 12Gi (swap 8Gi)                     |
| 커널       | 6.17.0-1016-oracle                  |
| OS       | Ubuntu 24.04.4 LTS (noble)           |

## 측정 결과

- [Rust 엔진 벤치마크 결과](result/rust-engine.md)
