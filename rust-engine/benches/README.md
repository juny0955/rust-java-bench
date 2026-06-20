# 벤치마크: 처리량, 레이턴시, 메모리

`rust-engine`의 `MatchingEngine::submit_limit_order` 성능을 측정하는 자체 벤치마크 하니스다. 외부 벤치마크 프레임워크 없이 `std`만 사용한다.

## 실행

```sh
cargo run --release --bin bench_runner
```

반드시 release 빌드로 실행해야 한다. debug 빌드에서는 벤치마크 값이 의미 없으므로 runner가 즉시 실패한다.

## 워크로드

- `ThinBook`: 기준가에서 멀리 떨어진 bounded range 가격대에 resting 주문을 생성해 체결보다 호가창 삽입/조회 비용을 주로 본다.
- `ActiveFill`: 기준가 근처 주문을 생성해 체결과 대기 주문이 섞이는 일반 활성 장세를 본다.
- `DeepSweepCross`: 여러 가격대의 maker 주문을 먼저 쌓고, 하나의 큰 taker 주문이 여러 가격대를 sweep하게 만들어 매칭 루프가 여러 번 반복되는 경로를 본다. Rust 메모리 워커 입력 파서는 과거 실행 스크립트 호환을 위해 legacy `WorstCaseCross` 표기도 같은 시나리오로 받아들인다.
- `BookGrowthWorst`: 체결되지 않는 buy/sell 주문을 기준가 양쪽의 새 가격대에 번갈아 쌓아 가격 레벨 트리와 호가창 메모리가 계속 증가하는 worst case를 본다. `ThinBook`이 bounded range resting-book pressure라면, 이 시나리오는 price-level/tree growth와 memory-pressure worst case다.

`ThinBook`/`ActiveFill`은 seed 기반 xorshift로 주문을 생성하므로 run마다 워크로드가 달라진다. `DeepSweepCross`와 `BookGrowthWorst`는 **seed를 사용하지 않는 완전 결정적(deterministic) 워크로드**다. 따라서 이 시나리오들의 10회 반복은 동일한 주문열을 재생하며, stddev는 워크로드 분산이 아니라 순수 타이밍 노이즈만 반영한다.

각 시나리오는 1,000,000건과 10,000,000건 스케일에서 10회 반복 측정한다. 각 (시나리오, 스케일) 조합은 측정 전에 워밍업을 수행하며, 워밍업 건수는 스케일에 비례한다(`scale / 10`, 최소 10,000건). 큰 스케일일수록 할당자·캐시·분기예측이 정상상태에 더 가깝게 데워진 상태에서 측정한다.

## 측정 방식

각 run은 같은 시나리오, seed, count를 사용해 세 가지 pass로 나뉜다.

- 처리량 pass: `submit_limit_order` 루프만 wall-clock으로 측정한다. 주문 생성과 per-order 레이턴시 기록은 처리량 측정에서 제외한다.
- 레이턴시 pass: 전체 주문 중 일정 간격으로 샘플링해 p50/p90/p99/p999/max를 계산한다.
- 메모리 pass: 레이턴시 샘플 저장 없이 batch 단위로 RSS를 측정한다. 엔진이 비어 있는 시작 시점의 RSS는 `baseline_rss_kb`로 따로 기록하고, `avg_rss_kb`/`peak_rss_kb`는 batch 처리 **이후**의 적재(loaded) 상태 샘플만으로 계산한다. baseline을 분리하지 않으면 빈 엔진 샘플이 평균을 끌어내린다.

  RSS는 프로세스 전역 값이고 할당자는 해제된 메모리를 OS에 즉시 반환하지 않으므로, 한 프로세스에서 여러 시나리오를 순차 측정하면 이전 시나리오(특히 체결이 없어 호가창이 끝없이 커지는 `ThinBook`)의 잔존 RSS가 다음 시나리오의 baseline을 오염시킨다. 이를 막기 위해 메모리 pass는 `(시나리오, 스케일, run)`마다 **별도 워커 프로세스**(`--mem-worker`로 자기 자신을 재실행)에서 측정한다. 각 워커는 깨끗한 baseline에서 시작하므로 `baseline_rss_kb`가 진짜 "빈 엔진"의 RSS를 반영한다. RSS를 지원하지 않는 OS에서는 워커를 띄우지 않고 RSS 컬럼을 빈 값으로 둔다.

### 타이머 오버헤드

레이턴시 pass는 샘플마다 `Instant::now()` + `Instant::elapsed()`를 호출하므로, 기록된 레이턴시에는 이 타이머 호출 비용 자체가 포함된다. submit 한 건이 수십 ns 수준이면 이 오버헤드가 무시할 수 없다. runner는 측정 시작 시 `timer_overhead_ns`(샘플당 `now()`+`elapsed()` 평균 비용)를 측정해 stdout에 출력한다. 레이턴시 percentile은 이 값만큼의 고정 오버헤드를 포함한 수치로 읽어야 하며, 엔진 단독 추정치가 필요하면 percentile에서 `timer_overhead_ns`를 빼면 된다. 이 값은 머신·OS에 따라 다르므로(특히 Windows QPC는 ~20–30ns) 매 실행 로그에서 확인한다.

RSS는 Linux에서만 `/proc/self/status`의 `VmRSS`로 측정한다. 지원하지 않는 OS에서는 RSS 컬럼을 빈 값으로 쓰고 `rss_supported=false`를 기록한다.

## 결과

결과 CSV는 `rust-engine/target/bench-results/{timestamp}.csv`에 저장된다. 이 경로는 빌드 산출물로 취급하며 커밋하지 않는다.

CSV 컬럼:

```text
scenario,scale,row_type,ops_sec,submit_elapsed_ms,p50_ns,p90_ns,p99_ns,p999_ns,max_ns,latency_sample_count,latency_sample_stride,baseline_rss_kb,peak_rss_kb,avg_rss_kb,rss_supported,target_os
```

- `row_type`: `run_0`..`run_9`, `mean`, `stddev`
- `ops_sec`: 처리량 pass의 submit loop 시간 기준 처리량
- `submit_elapsed_ms`: 처리량 pass의 submit loop elapsed time
- `latency_sample_count`: 레이턴시 계산에 사용한 샘플 수
- `latency_sample_stride`: 몇 주문마다 하나를 레이턴시 샘플로 기록했는지
- `baseline_rss_kb`: 주문 적재 전 빈 엔진의 RSS. `avg`/`peak` 계산에서 제외된다.
- `peak_rss_kb`, `avg_rss_kb`: 적재 상태 샘플 기준. RSS 지원 OS에서만 값이 채워진다.
- `stddev` row의 표준편차는 모표준편차(분모 `n`, `n=10`)다. 표본표준편차(`n-1`)가 아니다.

`timer_overhead_ns`는 CSV에 기록하지 않고 실행 로그(stdout)에만 출력한다. 실행 단위로 한 번만 측정하는 전역 값이라 행마다 중복 기록하지 않는다.

## Java 하니스와의 의미론 동일성

이 레포의 목적은 동일 워크로드에서 Rust 엔진과 Java 엔진을 **공정하게** 비교하는 것이다. `java-engine`의 벤치마크 하니스는 (구현 시) 아래 의미론을 이 Rust 하니스와 동일하게 맞춰야 한다. 한쪽만 다르면 비교가 무의미해진다.

- **시나리오·스케일·반복**: 동일한 `ThinBook`/`ActiveFill`/`DeepSweepCross`/`BookGrowthWorst`, 동일한 1,000,000 / 10,000,000 스케일, 동일한 10회 반복.
- **워크로드 생성**: 동일한 xorshift RNG와 seed 시퀀스로 **바이트 단위 동일한 주문열**을 생성한다. `DeepSweepCross`와 `BookGrowthWorst`는 양쪽 모두 seed 무관 결정적 생성이어야 한다. 바이트 단위 동일성은 아래 RNG 호출 순서 계약을 양쪽이 정확히 지킬 때만 성립한다.

  **RNG 호출 순서 계약** (Java 포팅이 가장 어긋나기 쉬운 지점):
  - **xorshift64**: 상태 `x`에 대해 `x ^= x << 13; x ^= x >> 7; x ^= x << 17` 순으로 갱신하고 갱신 후 값을 반환한다. `seed == 0`이면 상태를 골든레이쇼 상수 `0x9E37_79B9_7F4A_7C15`로 치환해 0 고착을 피한다.
  - **id 선할당**: 매 주문은 난수를 소비하기 **전에** `id`(= `sequence`)를 먼저 발급한다. `next_id`는 시나리오·체결 여부와 무관하게 **모든 주문마다 1씩** 증가한다.
  - **난수 소비 순서**: `ThinBook`/`ActiveFill`은 주문당 정확히 3회를 `side → offset(또는 jitter) → qty` 순서로 소비한다. `side`는 난수가 짝수면 `Buy`. `DeepSweepCross`와 `BookGrowthWorst`는 난수를 **전혀 소비하지 않으므로** `rng_state`를 건드리지 않는다(`next_id`만 증가).
  - 따라서 한 워크로드 안에서 `ThinBook`/`ActiveFill`만 `rng_state`를 전진시키고, `DeepSweepCross`/`BookGrowthWorst`는 전진시키지 않는다. Java도 동일하게 맞춰야 같은 seed에서 동일 주문열이 나온다.
- **3-pass 분리**: throughput / latency / memory를 각각 독립 엔진으로 재생한다. 처리량 측정에서 주문 생성과 레이턴시 기록을 제외하는 규칙도 동일해야 한다.
- **반환값 처리**: `submit_limit_order`가 반환하는 체결(trade) 컬렉션은 양쪽 모두 **사용하지 않고 폐기**한다. `ActiveFill`/`DeepSweepCross`처럼 체결이 발생하는 시나리오에서 trade 컬렉션 할당·해제 비용이 측정에 포함되는지 여부를 동일하게 맞춘다(현재 Rust는 포함).
- **워밍업**: 측정 전 워밍업을 수행하고, 워밍업 건수를 스케일에 비례시킨다. 단, JIT가 있는 Java는 정상상태(steady state) 도달에 필요한 워밍업이 Rust보다 훨씬 크므로, "스케일 비례"라는 정책은 공유하되 충분히 데워졌는지는 Java 쪽에서 별도로 검증해야 한다.
- **레이턴시 샘플링**: 동일한 target 샘플 수(100,000)와 stride 규칙, 동일한 percentile 정의(p50/p90/p99/p999/max)를 사용한다. 타이머 오버헤드도 동일하게 측정·보고한다.
- **메모리**: baseline(빈 엔진)을 분리하고 적재 상태만으로 avg/peak를 계산하는 규칙을 공유한다. 단 JVM은 RSS에 힙 예약·GC 동작이 섞이므로 해석에 주의한다.
- **집계**: 모표준편차(분모 `n`) 정의를 동일하게 사용한다.
