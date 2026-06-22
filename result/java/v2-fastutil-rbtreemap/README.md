# Java v2: OrderBook `TreeMap` → fastutil `Long2ObjectRBTreeMap`

측정일: 2026-06-22
실행 환경: 루트 [`README.md`](../../../README.md#실행-환경) 기준, **Rust 베이스라인과 동일 환경** (OCI, aarch64 Neoverse-N1, 2 vCPU, Ubuntu 24.04.4 LTS) — RSS 컬럼이 채워진 것으로 Linux 측정임이 확인됨
실행 명령: `./gradlew bench` (`java-engine/`)
측정 방식/CSV 컬럼 정의: [`java-engine/src/main/java/dev/junyoung/bench/README.md`](../../../java-engine/src/main/java/dev/junyoung/bench/README.md)
비교 대상: [`Rust 엔진 기준선`](../../rust-baseline/), 이전 버전: [`v1.1-scenario-update`](../v1.1-scenario-update/)

> **이번 측정은 첫 엔진 최적화 시도다.** `OrderBook`의 양측 가격 트리를 `java.util.TreeMap<Long, ArrayDeque<Order>>`에서
> fastutil `Long2ObjectRBTreeMap<ArrayDeque<Order>>`로 교체했다(가격 키 박싱 제거 목적). 워밍업·시나리오 구성은
> [`v1.1-scenario-update`](../v1.1-scenario-update/)와 동일하다. **결론부터: 클린 윈이 아니라 트레이드오프다.**
> 얕은 책(ThinBook/ActiveFill)은 빨라졌지만, 깊은 책(DeepSweepCross/BookGrowthWorst)은 ~2× 느려졌다.

## 처리량·레이턴시 요약 (mean ± stddev, n=10)

| 시나리오 | 스케일 | ops/s | p50 | p90 | p99 | p999 | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| ThinBook | 1,000,000 | 5,061,387 ± 816,045 | 180ns ± 20ns | 260ns ± 20ns | 516ns ± 107ns | 1,248ns ± 312ns | 131,945ns ± 178,758ns |
| ThinBook | 10,000,000 | 4,284,819 ± 249,327 | 232ns ± 16ns | 332ns ± 18ns | 484ns ± 28ns | 3,000ns ± 522ns | 159,449ns ± 235,780ns |
| ActiveFill | 1,000,000 | 9,916,926 ± 1,403,575 | 80ns | 160ns | 372ns ± 263ns | 1,044ns ± 910ns | 4,568,145ns ± 5,551,196ns |
| ActiveFill | 10,000,000 | 10,645,218 ± 1,511,304 | 80ns | 160ns | 372ns ± 62ns | 1,316ns ± 312ns | 232,433ns ± 229,332ns |
| DeepSweepCross | 1,000,000 | 2,722,928 ± 181,306 | 240ns | 280ns | 320ns | 38,168ns ± 18,691ns | 236,305ns ± 181,404ns |
| DeepSweepCross | 10,000,000 | 2,815,056 ± 12,970 | 240ns | 280ns | 336ns ± 20ns | 47,353ns ± 31,176ns | 631,895ns ± 1,252,182ns |
| BookGrowthWorst | 1,000,000 | 1,861,313 ± 86,761 | 564ns ± 12ns | 608ns ± 16ns | 684ns ± 12ns | 2,908ns ± 1,104ns | 56,912ns ± 50,558ns |
| BookGrowthWorst | 10,000,000 | 978,403 ± 75,116 | 800ns | 856ns ± 20ns | 2,372ns ± 247ns | 6,472ns ± 364ns | 20,764,212ns ± 61,673,548ns |

원본 CSV: [`raw.csv`](./raw.csv) (측정 인스턴스의 `java-engine/build/bench-results/1782095691.csv`에서 가져옴, Linux)

## JVM 진단·리소스 (mean)

| 시나리오 | 스케일 | gc (cnt/ms) | jit ms | alloc | heap peak / avg | RSS base / peak / avg |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| ThinBook | 1,000,000 | 0.7 / 30.3 | 6.8 | 37.1MB | 352,302 / 308,095 KB | 44,114 / 169,634 / 106,170 KB |
| ThinBook | 10,000,000 | 6.4 / 744.9 | 1.6 | 392.6MB | 1,357,238 / 968,612 KB | 44,128 / 926,860 / 507,093 KB |
| ActiveFill | 1,000,000 | 5.1 / 29.1 | 7.5 | 122.4MB | 137,672 / 86,572 KB | 44,076 / 138,646 / 116,167 KB |
| ActiveFill | 10,000,000 | 5.6 / 266.0 | 0.2 | 1,217.5MB | 1,091,584 / 665,432 KB | 44,174 / 940,888 / 451,718 KB |
| DeepSweepCross | 1,000,000 | 0.4 / 14.6 | 0.2 | 246.8MB | 1,053,286 / 812,954 KB | 44,158 / 175,556 / 134,899 KB |
| DeepSweepCross | 10,000,000 | 3.3 / 17.1 | 0.9 | 2,467.8MB | 1,282,844 / 834,184 KB | 44,097 / 346,546 / 292,847 KB |
| BookGrowthWorst | 1,000,000 | 0.5 / 11.0 | 1.0 | 176.0MB | 942,661 / 748,492 KB | 44,138 / 318,552 / 203,627 KB |
| BookGrowthWorst | 10,000,000 | **13.6 / 2,713.9** | 0.4 | 1,760.0MB | 2,931,794 / 2,032,592 KB | 44,053 / 2,410,566 / 1,248,908 KB |

## Rust 대비 비교 (동일 환경·동일 워크로드)

| 시나리오 | 스케일 | Rust ops/s | Java ops/s | Java/Rust | Rust peak RSS | Java peak RSS | RSS 배수 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| ThinBook | 1,000,000 | 7,110,043 | 5,061,387 | **71%** | 71,262KB | 169,634KB | 2.4× |
| ThinBook | 10,000,000 | 7,001,214 | 4,284,819 | **61%** | 690,280KB | 926,860KB | 1.3× |
| ActiveFill | 1,000,000 | 13,779,164 | 9,916,926 | **72%** | 20,116KB | 138,646KB | 6.9× |
| ActiveFill | 10,000,000 | 14,109,073 | 10,645,218 | **75%** | 104,960KB | 940,888KB | 9.0× |
| DeepSweepCross | 1,000,000 | 3,859,277 | 2,722,928 | **71%** | 6,831KB | 175,556KB | 25.7× |
| DeepSweepCross | 10,000,000 | 3,857,726 | 2,815,056 | **73%** | 6,859KB | 346,546KB | 50.5× |
| BookGrowthWorst | 1,000,000 | 5,231,770 | 1,861,313 | **36%** | 287,373KB | 318,552KB | 1.1× |
| BookGrowthWorst | 10,000,000 | 4,871,033 | 978,403 | **20%** | 2,815,277KB | 2,410,566KB | 0.9× |

## v1.1 대비 변화 (자료구조만 교체, 동일 하니스)

이번 변경의 순효과를 직접 보려면 이전 버전과 같은 환경의 ops/s를 비교한다.

| 시나리오 | 스케일 | v1.1 ops/s | v2 ops/s | 변화 | v1.1 Java/Rust | v2 Java/Rust |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| ThinBook | 1,000,000 | 4,500,796 | 5,061,387 | **+12.5%** | 63% | 71% |
| ThinBook | 10,000,000 | 3,416,818 | 4,284,819 | **+25.4%** | 49% | 61% |
| ActiveFill | 1,000,000 | 9,973,139 | 9,916,926 | −0.6% | 72% | 72% |
| ActiveFill | 10,000,000 | 9,902,751 | 10,645,218 | **+7.5%** | 70% | 75% |
| DeepSweepCross | 1,000,000 | 4,333,097 | 2,722,928 | **−37.2%** | 112% | 71% |
| DeepSweepCross | 10,000,000 | 4,394,784 | 2,815,056 | **−35.9%** | 114% | 73% |
| BookGrowthWorst | 1,000,000 | 3,527,828 | 1,861,313 | **−47.2%** | 67% | 36% |
| BookGrowthWorst | 10,000,000 | 1,896,875 | 978,403 | **−48.4%** | 39% | 20% |

## 관찰

### 얕은 책은 개선, 깊은 책은 회귀 — 가격 레벨 수가 부호를 가른다
- 얕은 책(ThinBook/ActiveFill)은 가격 레벨이 적어 트리 구조 변경(레벨 삽입/삭제)이 드물고, 매 접근의
  `Long` 박싱을 없앤 이득이 그대로 남는다 → ThinBook +12~25%, ActiveFill 10M +7.5%.
- 깊은 책(DeepSweepCross/BookGrowthWorst)은 서로 다른 가격 레벨을 끊임없이 만들고 지우는 워크로드라
  **구조 변경 비용이 지배적**이다 → DeepSweepCross −36%, BookGrowthWorst −47~48%. `DeepSweepCross`는
  v1.1에서 Rust를 앞섰는데(112%/114%) 이번에 71%/73%로 **우위를 잃었다.**
- 이 부호 반전은 두 가지를 시사한다. (1) 이번 측정의 회귀는 환경 노이즈가 아니다 — 노이즈라면 모든
  시나리오가 같은 방향으로 움직였어야 한다. (2) `Long2ObjectRBTreeMap`의 상수 인자는 **레벨 수에 따라**
  `TreeMap` 대비 유불리가 갈린다.

### 유력 가설: fastutil RB 트리의 스레드(threaded) 노드 유지 비용
- fastutil `Long2ObjectRBTreeMap`은 빠른 양방향 순회를 위해 각 노드에 선행/후행 스레드(predecessor/
  successor link)를 유지하는 **threaded red-black tree**다. 이 스레드는 `put`(새 레벨)·`remove`(빈 레벨)
  같은 **구조 변경마다** 갱신 비용을 치른다. `java.util.TreeMap`은 스레드가 없어 삽입/삭제 fixup이 더 가볍다.
- 깊은 책은 매칭 루프가 레벨 삽입/삭제를 대량으로 돌리므로, 박싱 제거 이득보다 스레드 유지 오버헤드가
  커져 순손실이 난다는 설명과 측정 방향이 일치한다. **다만 이는 가설이다** — 확정하려면 async-profiler 등으로
  핫 프레임(트리 fixup vs 박싱)을 직접 떠봐야 한다.

### BookGrowthWorst/10M은 여전히 GC pause가 가장 큰 비용
- `gc_count`/`gc_time_ms` mean 13.6회/2,713.9ms로 v1.1(14.4회/2,194.4ms)과 같은 자릿수다. `max_ns`에
  `run_8`(205,783,631ns)·`run_5`(194,811,357ns)·`run_9`(186,609,032ns) 등 ~200ms 단발 STW 스톨이
  그대로 보인다. 자료구조 교체는 이 GC pause 패턴을 해소하지 못했고(오히려 throughput 회귀와 겹쳐
  Java/Rust가 39%→20%로 더 벌어졌다), 이 워크로드의 본질적 비용은 메모리/GC임을 재확인한다.

## 참고·한계

- **검증된 것은 "무엇"이지 "왜"가 아니다.** 회귀 자체는 tight stddev로 확실하나(예: DeepSweepCross 10M
  ±12,970/2.8M), 스레드 노드 가설은 프로파일링으로 확정되지 않았다.
- 환경 캐비엇: 이번 측정의 `baseline_rss`는 ~44,100KB로 v1.1(~39,800KB)보다 약 4MB 높다. JVM 빌드/커널
  상태 차이로 보이며, throughput이 **방향성 있게**(얕은 책↑, 깊은 책↓) 갈린 점을 환경 균일 슬로다운으로는
  설명할 수 없으므로, 회귀의 원인은 자료구조 교체로 본다.
- 코드 변경: `OrderBook.java` 단일 파일(`TreeMap`→`Long2ObjectRBTreeMap`, primitive 접근자
  `firstLongKey`/`lastLongKey` 사용), fastutil 의존성 추가. 엔진 로직·워밍업·시나리오는 v1.1과 동일.
- per-run 값(run_0..run_9)은 [`raw.csv`](./raw.csv)에 있다.
