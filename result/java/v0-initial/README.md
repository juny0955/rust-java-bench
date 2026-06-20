# Java v0 초기 포팅 결과

측정일: 2026-06-20
실행 환경: 루트 [`README.md`](../../../README.md#실행-환경) 기준, **Rust 베이스라인과 동일 환경** (OCI, aarch64 Neoverse-N1, 2 vCPU, Ubuntu 24.04.4 LTS) — RSS 컬럼이 채워진 것으로 Linux 측정임이 확인됨
실행 명령: `./gradlew bench` (`java-engine/`)
측정 방식/CSV 컬럼 정의: [`java-engine/src/main/java/dev/junyoung/bench/README.md`](../../../java-engine/src/main/java/dev/junyoung/bench/README.md)
비교 대상: [`Rust 엔진 기준선`](../../rust-baseline/)

> 워크로드는 Rust 하니스와 바이트 단위로 동일하다(동일 seed·동일 주문열). 따라서 아래 수치는 **같은 입력**에
> 대한 두 언어의 차이로 읽을 수 있다. 본 문서는 stdout 요약 기준이며 p90/p999·per-run 값은 원본 CSV에 있다.

## 처리량·레이턴시 요약 (mean ± stddev, n=10)

| 시나리오 | 스케일 | ops/s | p50 | p90 | p99 | p999 | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| ThinBook | 1,000,000 | 4,122,459 ± 562,108 | 208ns | 300ns ± 20ns | 660ns ± 291ns | 1,412ns ± 513ns | 1,689,717ns ± 4,249,077ns |
| ThinBook | 10,000,000 | 3,476,331 ± 202,081 | 240ns | 356ns ± 12ns | 520ns ± 31ns | 3,236ns ± 713ns | 76,308ns ± 72,290ns |
| ActiveFill | 1,000,000 | 11,788,852 ± 2,877,457 | 80ns | 160ns | 480ns ± 336ns | 1,220ns ± 887ns | 43,988ns ± 17,103ns |
| ActiveFill | 10,000,000 | 9,822,064 ± 571,343 | 80ns | 160ns | 448ns ± 243ns | 1,456ns ± 759ns | 8,296,854ns ± 23,792,499ns |
| WorstCaseCross | 1,000,000 | 3,355,380 ± 468,087 | 120ns | 164ns ± 12ns | 344ns ± 352ns | 75,948ns ± 38,216ns | 2,404,985ns ± 2,730,081ns |
| WorstCaseCross | 10,000,000 | 4,347,532 ± 91,796 | 120ns | 160ns | 204ns ± 12ns | 48,337ns ± 33,943ns | 201,813ns ± 134,653ns |

원본 CSV: [`raw.csv`](./raw.csv) (측정 인스턴스의 `java-engine/build/bench-results/1781942253.csv`에서 가져옴, Linux)

## JVM 진단·리소스 (mean)

`gc`=수집횟수/정지시간, `jit`=처리량 pass 중 컴파일 시간, `alloc`=submit 구간 할당, `heap`=힙 사용(MXBean), `RSS`=상주 메모리(별도 워커 프로세스).

| 시나리오 | 스케일 | gc (cnt/ms) | jit ms | alloc | heap peak / avg | RSS base / peak / avg |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| ThinBook | 1,000,000 | 1.9 / 52 | 26.6 | 61.1MB | 279,302 / 213,402 KB | 39,851 / 155,944 / 112,013 KB |
| ThinBook | 10,000,000 | 6.3 / 765 | 1.5 | 632.6MB | 1,464,934 / 1,025,711 KB | 39,858 / 1,071,955 / 554,707 KB |
| ActiveFill | 1,000,000 | 0.1 / 14 | 29.7 | 137.0MB | 1,466,163 / 1,379,676 KB | 39,842 / 129,703 / 114,062 KB |
| ActiveFill | 10,000,000 | 10.7 / 229 | 0.5 | 1,361.2MB | 932,265 / 437,147 KB | 39,842 / 971,848 / 494,918 KB |
| WorstCaseCross | 1,000,000 | 17.2 / 55 | 0.5 | 270.8MB | 216,017 / 112,469 KB | 39,856 / 169,078 / 126,114 KB |
| WorstCaseCross | 10,000,000 | 11.5 / 48 | 1.0 | 2,707.5MB | 320,025 / 163,990 KB | 39,848 / 466,669 / 363,695 KB |

## Rust 대비 비교 (동일 환경·동일 워크로드)

| 시나리오 | 스케일 | Rust ops/s | Java ops/s | Java/Rust | Rust peak RSS | Java peak RSS | RSS 배수 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| ThinBook | 1,000,000 | 7,219,060 | 4,122,459 | **57%** | 71,261KB | 155,944KB | 2.2× |
| ThinBook | 10,000,000 | 7,044,906 | 3,476,331 | **49%** | 690,280KB | 1,071,955KB | 1.6× |
| ActiveFill | 1,000,000 | 13,921,520 | 11,788,852 | **85%** | 20,119KB | 129,703KB | 6.4× |
| ActiveFill | 10,000,000 | 14,236,476 | 9,822,064 | **69%** | 104,959KB | 971,848KB | 9.3× |
| WorstCaseCross | 1,000,000 | 3,897,419 | 3,355,380 | **86%** | 6,834KB | 169,078KB | 24.7× |
| WorstCaseCross | 10,000,000 | 3,893,413 | 4,347,532 | **112%** | 6,864KB | 466,669KB | 68.0× |

## 관찰

### 처리량: 워밍업이 격차의 큰 부분을 설명한다
- `jit_compile_ms`가 첫 run에 몰린다. `ActiveFill/1M`은 **run_0이 3.74M ops/s(jit=283ms)** 인데 run_1부터 **11~13M ops/s(jit≈0)** 로 점프한다. mean(11.79M)은 run_0에 끌려 내려간 값이고, **steady-state는 약 12.7M ops/s로 Rust(13.9M)의 ~91%**다. `ThinBook/1M`도 run_0~1(jit 123/139ms) 이후 안정화되며 steady-state ≈ 4.34M(Rust의 ~60%).
- 즉 "Java가 Rust를 따라가려면"의 1차 조건은 **충분한 JIT 워밍업**이다. mean만 보면 격차가 과장된다.

### 시나리오별 격차의 성격이 다르다
- **삽입 우위(ThinBook)**: 워밍업 후에도 Rust의 49~60% 수준. 체결 없이 주문이 계속 쌓여 `TreeMap`/`ArrayDeque`의 객체·노드 오버헤드와 할당이 지배한다. 자료구조·할당이 Java의 약점이 가장 크게 드러나는 구간.
- **체결 우위(ActiveFill)**: 1M steady ~91%로 Rust에 가장 근접. 호가창이 얕게 유지돼 할당/탐색 비용이 작다. 다만 10M에서는 69%로 벌어지는데, 이때 GC(10.7회/229ms)와 메모리 대역폭 영향이 커진다.
- **worst case(WorstCaseCross)**: 10M에서 **Java가 Rust보다 12% 빠르다(4.35M vs 3.89M, 양쪽 stddev ≤2%로 유의)**. sweep 매칭 루프가 충분히 데워지면 C2가 타이트하게 최적화하고, 결정적 워크로드라 분기예측도 잘 맞는다. 반면 1M(3.36M)은 GC(17.2회)에 눌려 10M보다도 느린 역전이 나타난다.
- WorstCaseCross의 **p999가 압도적으로 크고 변동도 크다**(1M: 75,948ns±38,216ns, 10M: 48,337ns±33,943ns — p99의 200배 이상). Rust는 이 시나리오에서 p999 stddev만 크게 튀는 정도(31,776ns±21,141ns @1M)인데, Java는 절댓값 자체가 한 자릿수 더 크다. sweep taker가 1,000개 가격대를 매칭하는 동안 GC 세이프포인트에 걸리면 그 한 건의 레이턴시가 통째로 부풀기 때문으로 보인다 — Rust에는 이런 정지 메커니즘이 없다.

### 메모리: Java의 가장 큰 차이
- **baseline RSS ~39.8MB**는 빈 엔진이 아니라 JVM 자체 상주 비용이다(Rust는 수 MB). 작은 워크로드일수록 이 고정비가 배수를 키운다.
- **WorstCaseCross의 RSS 배수가 압도적**: Rust는 호가창 깊이가 항상 `SWEEP_LEVELS`(1,000)에서 평형이라 1M/10M 모두 ~6.8MB로 일정한데, Java는 1M 169MB·10M 467MB로 커진다(**24.7×~68×**). live set은 작지만 GC가 처리량을 위해 메모리를 늦게 반환해 상주 메모리가 부풀어 오른다. `heap_used_avg`(10M 164MB)도 live보다 크게 잡혀 세대 간 누적 가비지를 보여준다.
- `alloc_bytes`로 본 할당 압력은 **WorstCaseCross > ActiveFill > ThinBook**(10M 기준 2.71GB / 1.36GB / 0.63GB). 체결이 많을수록 trade 객체·리스트 할당이 늘기 때문이며, 이 할당 압력이 GC 빈도·RSS 증가의 직접 원인이다.

### 안정성
- 대부분 ops_sec stddev는 평균의 1.4~6% 수준이나, **워밍업이 섞인 run_0 때문에 ThinBook/ActiveFill 1M의 stddev가 크다**(ActiveFill 1M 24%). max latency는 GC/세이프포인트로 단발성 스파이크가 크게 튄다(ActiveFill 10M max ~8.3ms ± 23.8ms).

## 참고·한계

- **mean vs steady-state**: 한 JVM 프로세스가 모든 시나리오를 순차 실행하므로 JIT 컴파일은 프로세스 전역에 누적된다. 첫 (시나리오,스케일)의 run_0가 가장 큰 워밍업 비용을 떠안는다. 공정 비교에는 steady-state(run_0 제외) 해석을 병행해야 한다.
- **RSS vs heap_used**: RSS는 별도 워커 프로세스, heap_used는 메인 프로세스 측정이라 시점·GC 타이밍이 달라 두 값은 점대점으로 비교되지 않는다. RSS는 OS 상주, heap_used는 GC 관리 힙의 서로 다른 신호로 본다.
- per-run 값(run_0..run_9)은 원본 CSV에 있다. 위 "워밍업이 격차의 큰 부분" 절의 run_0/run_1 수치는 이 CSV에서 직접 확인한 값이다.
