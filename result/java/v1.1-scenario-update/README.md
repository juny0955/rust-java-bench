# Java v1.1 시나리오 갱신 재측정 결과

측정일: 2026-06-20
실행 환경: 루트 [`README.md`](../../../README.md#실행-환경) 기준, **Rust 베이스라인과 동일 환경** (OCI, aarch64 Neoverse-N1, 2 vCPU, Ubuntu 24.04.4 LTS) — RSS 컬럼이 채워진 것으로 Linux 측정임이 확인됨
실행 명령: `./gradlew bench` (`java-engine/`)
측정 방식/CSV 컬럼 정의: [`java-engine/src/main/java/dev/junyoung/bench/README.md`](../../../java-engine/src/main/java/dev/junyoung/bench/README.md)
비교 대상: [`Rust 엔진 기준선`](../../rust-baseline/), 이전 버전: [`v1-adaptive-warmup`](../v1-adaptive-warmup/)

> **이번 측정은 "개선" 버전이 아니다.** 워밍업 로직·엔진 동작은 [`v1-adaptive-warmup`](../v1-adaptive-warmup/)과
> 완전히 동일하다. 달라진 것은 시나리오 구성뿐이다: `WorstCaseCross`가 `DeepSweepCross`로 이름이 바뀌고,
> 가격 레벨 트리가 끝없이 커지는 memory-pressure worst case `BookGrowthWorst`가 새로 추가됐다(Rust 측은
> [`result/rust-baseline/`](../../rust-baseline/)에서 이미 같은 갱신을 반영). 그래서 메이저 버전을 올리지 않고
> `v1-adaptive-warmup`의 부버전인 **v1.1**로 기록한다.

## 처리량·레이턴시 요약 (mean ± stddev, n=10)

| 시나리오 | 스케일 | ops/s | p50 | p90 | p99 | p999 | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| ThinBook | 1,000,000 | 4,500,796 ± 460,969 | 212ns ± 18ns | 300ns ± 20ns | 732ns ± 312ns | 1,844ns ± 951ns | 73,808ns ± 92,499ns |
| ThinBook | 10,000,000 | 3,416,818 ± 287,338 | 248ns ± 16ns | 372ns ± 26ns | 604ns ± 163ns | 3,548ns ± 677ns | 249,217ns ± 545,830ns |
| ActiveFill | 1,000,000 | 9,973,139 ± 1,832,467 | 80ns | 160ns | 608ns ± 407ns | 1,400ns ± 846ns | 1,880,631ns ± 3,220,414ns |
| ActiveFill | 10,000,000 | 9,902,751 ± 1,243,306 | 80ns | 160ns | 376ns ± 212ns | 1,052ns ± 373ns | 221,641ns ± 408,214ns |
| DeepSweepCross | 1,000,000 | 4,333,097 ± 192,921 | 120ns | 160ns | 312ns ± 336ns | 34,924ns ± 12,687ns | 168,725ns ± 30,724ns |
| DeepSweepCross | 10,000,000 | 4,394,784 ± 21,880 | 120ns | 160ns | 200ns | 44,232ns ± 24,572ns | 272,266ns ± 188,163ns |
| BookGrowthWorst | 1,000,000 | 3,527,828 ± 102,219 | 280ns | 320ns | 400ns | 688ns ± 16ns | 58,640ns ± 46,840ns |
| BookGrowthWorst | 10,000,000 | 1,896,875 ± 156,515 | 320ns | 376ns ± 20ns | 1,268ns ± 202ns | 6,064ns ± 961ns | 57,386,690ns ± 87,134,556ns |

원본 CSV: [`raw.csv`](./raw.csv) (측정 인스턴스의 `java-engine/build/bench-results/1781963268.csv`에서 가져옴, Linux)

## JVM 진단·리소스 (mean)

| 시나리오 | 스케일 | gc (cnt/ms) | jit ms | alloc | heap peak / avg | RSS base / peak / avg |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| ThinBook | 1,000,000 | 0.9 / 17.9 | 2.6 | 61.1MB | 362,760 / 272,466 KB | 39,858 / 156,947 / 112,226 KB |
| ThinBook | 10,000,000 | 6.4 / 756 | 1.2 | 632.6MB | 1,530,439 / 1,017,413 KB | 39,864 / 1,086,579 / 555,338 KB |
| ActiveFill | 1,000,000 | 3.2 / 20.9 | 2.4 | 136.9MB | 225,996 / 140,972 KB | 39,712 / 130,126 / 114,330 KB |
| ActiveFill | 10,000,000 | 6 / 276.9 | 0.4 | 1,361.2MB | 1,304,678 / 787,209 KB | 39,851 / 991,083 / 495,877 KB |
| DeepSweepCross | 1,000,000 | 0.2 / 10.7 | 0.5 | 270.8MB | 1,214,054 / 986,563 KB | 39,713 / 170,611 / 126,276 KB |
| DeepSweepCross | 10,000,000 | 2.9 / 18.9 | 0.6 | 2,707.5MB | 1,547,944 / 996,018 KB | 39,860 / 472,370 / 369,995 KB |
| BookGrowthWorst | 1,000,000 | 0 / 0 | 2.9 | 200.0MB | 1,194,900 / 1,080,601 KB | 39,846 / 334,461 / 214,540 KB |
| BookGrowthWorst | 10,000,000 | **14.4 / 2,194.4** | 0.9 | 2,000.0MB | 2,947,418 / 1,827,043 KB | 39,841 / 2,596,732 / 1,379,798 KB |

`BookGrowthWorst/10M`의 GC 정지 시간이 mean **2,194ms**로 다른 모든 (시나리오, 스케일) 조합보다
한 자릿수 이상 크다 — throughput pass 전체에서 GC만 2초 넘게 쓴다는 뜻이다.

## Rust 대비 비교 (동일 환경·동일 워크로드)

| 시나리오 | 스케일 | Rust ops/s | Java ops/s | Java/Rust | Rust peak RSS | Java peak RSS | RSS 배수 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| ThinBook | 1,000,000 | 7,110,043 | 4,500,796 | **63%** | 71,262KB | 156,947KB | 2.2× |
| ThinBook | 10,000,000 | 7,001,214 | 3,416,818 | **49%** | 690,280KB | 1,086,579KB | 1.6× |
| ActiveFill | 1,000,000 | 13,779,164 | 9,973,139 | **72%** | 20,116KB | 130,126KB | 6.5× |
| ActiveFill | 10,000,000 | 14,109,073 | 9,902,751 | **70%** | 104,960KB | 991,083KB | 9.4× |
| DeepSweepCross | 1,000,000 | 3,859,277 | 4,333,097 | **112%** | 6,831KB | 170,611KB | 25.0× |
| DeepSweepCross | 10,000,000 | 3,857,726 | 4,394,784 | **114%** | 6,859KB | 472,370KB | 68.9× |
| BookGrowthWorst | 1,000,000 | 5,231,770 | 3,527,828 | **67%** | 287,373KB | 334,461KB | 1.2× |
| BookGrowthWorst | 10,000,000 | 4,871,033 | 1,896,875 | **39%** | 2,815,277KB | 2,596,732KB | 0.9× |

## 관찰

### BookGrowthWorst/10M이 가장 큰 격차(39%)를 보이고, 원인은 RSS가 아니라 GC 정지 시간이다
- peak RSS는 Java(2,596,732KB)가 Rust(2,815,277KB)보다 오히려 **작다**(0.9×) — 메모리 점유 자체는
  비슷한 수준인데 ops/s는 Rust의 39%에 그친다. 차이를 설명하는 신호는 `gc_count`/`gc_time_ms`
  mean 14.4회/2,194ms다 — throughput pass(submit loop) 동안 GC가 2초 넘게 멈춰서고 있다.
- 원본 CSV의 `max_ns`를 보면 `run_5`(194,811,357ns)·`run_7`(189,949,091ns)·`run_9`(186,609,032ns)
  세 run이 약 190ms 단발 스톨을 기록한다. 나머지 run(49k~852k ns)과 100배 이상 차이가 나는 명백한
  이상치로, 풀 GC 혹은 긴 STW(stop-the-world) 컴팩션으로 보인다. Rust에는 이런 메커니즘이 없으므로
  RSS 배수(0.9×)만 보면 격차를 과소평가하게 된다 — Java 메모리 worst case는 RSS가 아니라
  **GC pause 누적**으로 비용을 지불한다는 뜻.

### DeepSweepCross는 v1과 동일하게 Java가 Rust를 앞선다
- 1M 112%, 10M 114% — `v1-adaptive-warmup`의 `WorstCaseCross`(103%/109%)와 같은 방향, 소폭 더
  앞섰다. `jit_compile_ms` mean이 0.5~0.6ms로 매우 낮아 충분히 데워진 상태에서 측정됐고, sweep
  matching loop는 JIT가 완전히 컴파일되면 Java가 강세를 보이는 패턴이 재확인된다.

### ThinBook은 노이즈 범위, ActiveFill은 v1보다 하락했지만 알고리즘 변경 없음
- `ThinBook`은 v1(61%/47%) 대비 63%/49%로 거의 같다.
- `ActiveFill`은 v1(82%/73%) 대비 72%/70%로 떨어졌는데, `ActiveFill/1M`의 stddev가
  1,832,467(ops/s 평균의 약 18%)로 매우 크다 — 원본 CSV에서 `run_2`(9.05M)~`run_1`(13.36M)까지
  run 간 변동이 크고, `jit_compile_ms`는 모든 run에서 0~14ms로 낮아 워밍업 부족이 원인은 아니다.
  엔진/워밍업 로직을 바꾸지 않았으므로 이 하락은 **공유 인스턴스의 run-to-run 노이즈**로 해석한다.

## 참고·한계

- 본 측정은 `v1-adaptive-warmup`과 같은 하니스를 사용한 **재측정**이다. ThinBook/ActiveFill/
  DeepSweepCross의 ops/s 차이는 알고리즘 변경이 아니라 측정 시점의 인스턴스 노이즈로 봐야 한다.
  `BookGrowthWorst`만 이번에 처음 측정됐다(이전 버전에는 해당 시나리오가 없었음).
- per-run 값(run_0..run_9)은 원본 CSV에 있다. 위 GC 스톨 이상치(run_5/7/9)는 이 CSV에서 직접
  확인한 값이다.
