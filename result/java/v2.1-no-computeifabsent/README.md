# Java v2.1: insert 경로에서 `computeIfAbsent` 제거 (수동 get/put)

측정일: 2026-06-22
실행 환경: 루트 [`README.md`](../../../README.md#실행-환경) 기준, **Rust 베이스라인과 동일 환경** (OCI, aarch64 Neoverse-N1, 2 vCPU, Ubuntu 24.04.4 LTS) — RSS 컬럼이 채워진 것으로 Linux 측정임이 확인됨
실행 명령: `./gradlew bench` (`java-engine/`)
측정 방식/CSV 컬럼 정의: [`java-engine/src/main/java/dev/junyoung/bench/README.md`](../../../java-engine/src/main/java/dev/junyoung/bench/README.md)
비교 대상: [`Rust 엔진 기준선`](../../rust-baseline/), 이전 버전: [`v2-fastutil-rbtreemap`](../v2-fastutil-rbtreemap/), 원조 TreeMap: [`v1.1-scenario-update`](../v1.1-scenario-update/)

> **v2의 `insert` 경로에서 `computeIfAbsent`만 수동 `get`/null 체크/`put`으로 바꾼, 단일 변수 측정이다.**
> 가격 레벨 `ArrayDeque`의 초기 용량은 기본값(16) 그대로 두었다 — 그래서 **`alloc_bytes`가 v2와 바이트
> 단위까지 동일**하다(예: BookGrowthWorst/1M 176,000,560, DeepSweepCross/1M 246,777,712). 즉 이 버전의
> throughput 변화는 메모리/GC가 아니라 **순수 insert 호출 비용**만 반영한다. 자료구조
> (`Long2ObjectRBTreeMap`)·워밍업·시나리오는 v2와 같다.

## 처리량·레이턴시 요약 (mean ± stddev, n=10)

| 시나리오 | 스케일 | ops/s | p50 | p90 | p99 | p999 | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| ThinBook | 1,000,000 | 5,229,400 ± 555,472 | 208ns ± 16ns | 304ns ± 20ns | 544ns ± 105ns | 1,384ns ± 448ns | 58,632ns ± 64,858ns |
| ThinBook | 10,000,000 | 4,015,064 ± 283,775 | 240ns | 368ns ± 16ns | 552ns ± 24ns | 3,304ns ± 532ns | 55,452ns ± 32,571ns |
| ActiveFill | 1,000,000 | 9,613,293 ± 1,232,166 | 80ns | 160ns | 664ns ± 411ns | 1,652ns ± 1,028ns | 523,687ns ± 720,135ns |
| ActiveFill | 10,000,000 | 9,741,777 ± 414,262 | 80ns | 160ns | 564ns ± 232ns | 1,640ns ± 100ns | 968,342ns ± 1,297,380ns |
| DeepSweepCross | 1,000,000 | 3,146,725 ± 32,314 | 200ns | 240ns | 280ns | 31,064ns ± 4,911ns | 184,477ns ± 42,614ns |
| DeepSweepCross | 10,000,000 | 3,165,072 ± 7,280 | 200ns | 240ns | 280ns | 53,557ns ± 43,205ns | 856,669ns ± 1,710,245ns |
| BookGrowthWorst | 1,000,000 | 1,891,344 ± 302,787 | 464ns ± 27ns | 560ns ± 64ns | 860ns ± 561ns | 1,776ns ± 1,054ns | 4,696,294ns ± 13,713,698ns |
| BookGrowthWorst | 10,000,000 | 1,159,160 ± 77,312 | 680ns | 736ns ± 20ns | 2,376ns ± 37ns | 6,348ns ± 581ns | 180,549ns ± 257,797ns |

원본 CSV: [`raw.csv`](./raw.csv) (측정 인스턴스의 `java-engine/build/bench-results/1782103877.csv`에서 가져옴, Linux)

## JVM 진단·리소스 (mean)

| 시나리오 | 스케일 | gc (cnt/ms) | jit ms | alloc | heap peak / avg | RSS base / peak / avg |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| ThinBook | 1,000,000 | 0.9 / 31.0 | 2.8 | 37.1MB | 274,044 / 231,141 KB | 44,128 / 169,529 / 106,072 KB |
| ThinBook | 10,000,000 | 7.3 / 773.8 | 1.7 | 392.6MB | 1,169,652 / 828,380 KB | 44,147 / 881,936 / 504,161 KB |
| ActiveFill | 1,000,000 | 3.8 / 21.0 | 8.6 | 122.4MB | 115,364 / 76,084 KB | 44,178 / 138,570 / 116,205 KB |
| ActiveFill | 10,000,000 | 13.3 / 254.7 | 0.6 | 1,217.5MB | 574,213 / 274,909 KB | 44,148 / 937,098 / 451,519 KB |
| DeepSweepCross | 1,000,000 | 0.4 / 2.1 | 0.2 | 246.8MB | 935,716 / 720,992 KB | 44,131 / 175,712 / 135,077 KB |
| DeepSweepCross | 10,000,000 | 3.7 / 10.4 | 0.7 | 2,467.8MB | 1,168,421 / 757,813 KB | 44,093 / 367,030 / 293,596 KB |
| BookGrowthWorst | 1,000,000 | 0.9 / 94.5 | 0.7 | 176.0MB | 1,154,911 / 1,002,552 KB | 44,100 / 318,338 / 203,335 KB |
| BookGrowthWorst | 10,000,000 | **13.6 / 2,610.2** | 0.3 | 1,760.0MB | 2,934,495 / 2,022,994 KB | 44,118 / 2,265,583 / 1,252,232 KB |

## Rust 대비 비교 (동일 환경·동일 워크로드)

| 시나리오 | 스케일 | Rust ops/s | Java ops/s | Java/Rust | Rust peak RSS | Java peak RSS | RSS 배수 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| ThinBook | 1,000,000 | 7,110,043 | 5,229,400 | **74%** | 71,262KB | 169,529KB | 2.4× |
| ThinBook | 10,000,000 | 7,001,214 | 4,015,064 | **57%** | 690,280KB | 881,936KB | 1.3× |
| ActiveFill | 1,000,000 | 13,779,164 | 9,613,293 | **70%** | 20,116KB | 138,570KB | 6.9× |
| ActiveFill | 10,000,000 | 14,109,073 | 9,741,777 | **69%** | 104,960KB | 937,098KB | 8.9× |
| DeepSweepCross | 1,000,000 | 3,859,277 | 3,146,725 | **82%** | 6,831KB | 175,712KB | 25.7× |
| DeepSweepCross | 10,000,000 | 3,857,726 | 3,165,072 | **82%** | 6,859KB | 367,030KB | 53.5× |
| BookGrowthWorst | 1,000,000 | 5,231,770 | 1,891,344 | **36%** | 287,373KB | 318,338KB | 1.1× |
| BookGrowthWorst | 10,000,000 | 4,871,033 | 1,159,160 | **24%** | 2,815,277KB | 2,265,583KB | 0.80× |

## v2 대비 변화 (insert 호출 경로만 변경, alloc 동일)

`alloc`이 v2와 완전히 같으므로 아래 throughput 차이는 메모리/GC가 아닌 순수 insert 비용 차이다.

| 시나리오 | 스케일 | v2 ops/s | v2.1 ops/s | 변화 | v2 alloc | v2.1 alloc |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| ThinBook | 1,000,000 | 5,061,387 | 5,229,400 | +3.3% | 37.1MB | 37.1MB |
| ThinBook | 10,000,000 | 4,284,819 | 4,015,064 | −6.3% | 392.6MB | 392.6MB |
| ActiveFill | 1,000,000 | 9,916,926 | 9,613,293 | −3.1% | 122.4MB | 122.4MB |
| ActiveFill | 10,000,000 | 10,645,218 | 9,741,777 | −8.5% | 1,217.5MB | 1,217.5MB |
| DeepSweepCross | 1,000,000 | 2,722,928 | 3,146,725 | **+15.6%** | 246.8MB | 246.8MB |
| DeepSweepCross | 10,000,000 | 2,815,056 | 3,165,072 | **+12.4%** | 2,467.8MB | 2,467.8MB |
| BookGrowthWorst | 1,000,000 | 1,861,313 | 1,891,344 | +1.6% | 176.0MB | 176.0MB |
| BookGrowthWorst | 10,000,000 | 978,403 | 1,159,160 | **+18.5%** | 1,760.0MB | 1,760.0MB |

## v1.1(원조 TreeMap) 대비 — RBTreeMap 방향의 누적 순효과

| 시나리오 | 스케일 | v1.1 ops/s | v2.1 ops/s | 변화 | v1.1 Java/Rust | v2.1 Java/Rust |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| ThinBook | 1,000,000 | 4,500,796 | 5,229,400 | **+16.2%** | 63% | 74% |
| ThinBook | 10,000,000 | 3,416,818 | 4,015,064 | **+17.5%** | 49% | 57% |
| ActiveFill | 1,000,000 | 9,973,139 | 9,613,293 | −3.6% | 72% | 70% |
| ActiveFill | 10,000,000 | 9,902,751 | 9,741,777 | −1.6% | 70% | 69% |
| DeepSweepCross | 1,000,000 | 4,333,097 | 3,146,725 | **−27.4%** | 112% | 82% |
| DeepSweepCross | 10,000,000 | 4,394,784 | 3,165,072 | **−28.0%** | 114% | 82% |
| BookGrowthWorst | 1,000,000 | 3,527,828 | 1,891,344 | **−46.4%** | 67% | 36% |
| BookGrowthWorst | 10,000,000 | 1,896,875 | 1,159,160 | **−38.9%** | 39% | 24% |

## 관찰

### `computeIfAbsent` 제거는 깊은 책을 순수 CPU 경로로 끌어올린다 (할당 변화 0)
- `alloc_bytes`가 v2와 바이트 단위까지 동일한데도 DeepSweepCross가 **+15.6%/+12.4%** 올랐고, 그 stddev는
  1M 32,314(1.0%)·10M 7,280(0.23%)으로 **매우 단단하다** — 노이즈가 아니라 확실한 신호다.
  BookGrowthWorst/10M도 +18.5%지만 이쪽은 GC 지배 워크로드라 변동(±77k, 6.6%)이 커 신뢰도는 낮다.
- 할당이 같으니 이득의 출처는 GC가 아니라 insert 호출 비용이다. **유력 가설**: fastutil
  `computeIfAbsent(long, LongFunction)`의 부기(존재 여부 확인 + `defaultReturnValue` 비교 + `LongFunction`
  람다 간접호출)가 수동 `get`+`put`보다 무겁고, 깊은 책처럼 새 레벨을 대량으로 만드는 워크로드에서
  그 per-insert 오버헤드가 누적된다. 확정하려면 프로파일링이 필요하다.

### 그래도 원조 TreeMap보다는 여전히 느리다
- v1.1(TreeMap) 대비 DeepSweepCross −27~28%, BookGrowthWorst −39~46%. v2(−36~48%)보다 좁혀졌지만
  부호는 그대로 음수다. `Long2ObjectRBTreeMap`의 깊은 책 구조변경 페널티([`v2`](../v2-fastutil-rbtreemap/)
  문서의 threaded 노드 가설)는 insert 경로 손질만으로 메우지 못한다.
- 반대로 얕은 책은 v1.1 대비 ThinBook +16~18%로 분명히 앞선다. **워크로드가 부호를 가른다**는 결론은 유지된다.

### 얕은 책 10M의 v2 대비 소폭 하락은 노이즈
- ThinBook/10M(−6.3%)·ActiveFill/10M(−8.5%)은 할당이 동일하고 insert 경로가 더 가벼워졌는데도 내려갔다 —
  알고리즘 근거가 없다. 공유 2-vCPU 인스턴스의 run-to-run 편차로 본다(ThinBook/1M은 같은 변경으로 +3.3%).

## 참고·한계

- **단일 변수 측정의 장점**: 직전 시도와 달리 용량 힌트를 섞지 않아 `alloc`이 v2와 동일하다. 덕분에
  "computeIfAbsent 제거"의 기여를 메모리 효과와 깨끗이 분리할 수 있었다.
- 할당은 그대로이므로 **메모리 측 레버(레벨 `ArrayDeque` 초기 용량 등)는 아직 손대지 않은 직교 축**이다 —
  깊은 책의 GC 비용(BookGrowthWorst/10M `gc_time_ms` 2,610ms)을 줄이려면 별도 변경이 필요하다.
- 환경/노이즈 캐비엇은 [`v2`](../v2-fastutil-rbtreemap/) 문서와 동일하다(공유 인스턴스, `baseline_rss` ~44MB).
- per-run 값(run_0..run_9)은 [`raw.csv`](./raw.csv)에 있다.
