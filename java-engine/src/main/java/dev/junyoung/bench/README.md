# 벤치마크: 처리량, 레이턴시, 메모리 + JVM 진단

`java-engine`의 `MatchingEngine.submitLimitOrder` 성능을 측정하는 자체 벤치마크 하니스다.
외부 벤치마크 프레임워크(JMH 등) 없이 JDK 표준만 사용한다. `rust-engine/benches/bench_runner`와
**워크로드·측정 의미론이 동일**하며, 거기에 **JVM 고유 병목/리소스 진단**을 더한다.

> 이 비교의 초점은 측정 정밀도가 아니라 **Rust와 Java의 언어적 차이**, 그리고 **Java가 Rust 성능을
> 따라가려면 어디까지 최적화해야 하는지**를 분석·학습하는 데 있다. 측정 노이즈는 결과를 심하게
> 오염시키지 않는 범위에서 허용한다. 단, 두 엔진이 **바이트 단위로 동일한 주문열**을 받는다는
> 공정성 전제는 양보하지 않는다.

## 실행

```sh
./gradlew bench
```

JIT가 켜져 있어야 한다(`-Xint` 감지 시 즉시 실패). Gradle `JavaExec` 태스크라 기본적으로 JIT가 켜진다.

## 워크로드

`rust-engine`과 동일하다.

- `ThinBook`: 기준가에서 먼 주문으로 체결보다 호가창 삽입/조회 비용을 본다.
- `ActiveFill`: 기준가 근처 주문으로 체결과 대기가 섞이는 활성 장세를 본다.
- `DeepSweepCross`: 여러 가격대 maker를 쌓고 하나의 큰 taker가 sweep하게 만들어 매칭 루프를 본다.
  **seed를 사용하지 않는 완전 결정적** 워크로드다.
  기존 결과/스크립트 호환을 위해 입력 label `WorstCaseCross`도 같은 시나리오로 파싱한다.

각 시나리오는 1,000,000 / 10,000,000 스케일에서 10회 반복하며, 측정 전 스케일 비례
(`max(scale/10, 10,000)`) 워밍업 pass를 수행한다. 고정 1-pass 워밍업으로는 JVM JIT가 C2까지
컴파일을 끝내지 못해 `run_0`/`run_1`의 ops_sec이 낮고 불안정해지는 문제가 있어, 같은 크기의
워밍업 pass를 **적응형으로 반복**한다: `CompilationMXBean`의 누적 컴파일 시간 델타가 연속 2회
0ms이면 안정화로 보고 멈추고(최대 10패스 cap), 그 직후 비동기 JIT 정리 시간을 벌기 위해 100~200ms
슬립을 한 번 둔다. `CompilationMXBean`이 컴파일 시간 모니터링을 지원하지 않는 환경에서는 cap인
10패스를 그대로 수행한다. 측정 중 `jit_compile_ms ≈ 0`이면 충분히 데워졌다는 방증으로 본다.

### Rust와의 byte 단위 동일성

같은 seed에서 Rust와 동일한 주문열을 생성하려면 RNG 호출 순서 계약을 정확히 지켜야 한다
(`WorkloadGenerator`). 언어 차이로 인한 필수 변환:

- **부호 없는 나머지**: Rust `u64 % n` → Java `Long.remainderUnsigned(x, n)`
  (`long % n`은 음수에서 음수 결과라 byte 동일성이 깨진다).
- **xorshift `x >> 7`**: Rust u64 logical shift → Java `>>>`.
- **짝수 판정** `is_multiple_of(2)` → `(x & 1L) == 0`.

`WorkloadGeneratorTest`가 Rust 생성기에서 덤프한 골든값으로 이를 크로스 검증한다.

## 측정 방식 (3-pass)

각 run은 같은 (시나리오, seed, count)를 독립 엔진으로 세 번 재생한다.

- **처리량 pass**: `submitLimitOrder` 루프만 wall-clock으로 측정한다. 주문 생성과 per-order 레이턴시
  기록은 제외한다. JVM 진단(GC·JIT·할당·힙)도 이 pass에 통합 측정한다(per-order 오버헤드 0).
- **레이턴시 pass**: 일정 간격(stride) 샘플로 p50/p90/p99/p999/max를 계산한다(타깃 100,000 샘플).
- **메모리 pass**: RSS는 Linux에서만 `(시나리오, 스케일, run)`마다 별도 워커 프로세스로 측정한다
  (깨끗한 baseline). 그 외 OS(예: macOS)에서는 워커를 띄우지 않고 RSS 컬럼을 비운다.

### 타이머 오버헤드

레이턴시 pass는 샘플마다 `System.nanoTime()`을 두 번 호출하므로 기록값에 타이머 비용이 포함된다.
runner는 시작 시 `timer_overhead_ns`를 측정해 stdout에 출력한다(CSV에는 기록하지 않는다).
percentile은 이 고정 오버헤드를 포함한 값으로 읽고, 엔진 단독 추정이 필요하면 빼면 된다.

## 결과 CSV

`java-engine/build/bench-results/{epoch}.csv`에 저장된다(빌드 산출물, 미커밋).

**앞 17개 컬럼은 Rust `csv_header()`와 문자 단위로 동일**하다. 그 뒤 6개가 Java 전용 진단 컬럼이다.

```text
scenario,scale,row_type,ops_sec,submit_elapsed_ms,p50_ns,p90_ns,p99_ns,p999_ns,max_ns,
latency_sample_count,latency_sample_stride,baseline_rss_kb,peak_rss_kb,avg_rss_kb,rss_supported,
target_os,gc_count,gc_time_ms,jit_compile_ms,alloc_bytes,heap_used_peak_kb,heap_used_avg_kb
```

- `row_type`: `run_0`..`run_9`, `mean`, `stddev`. `stddev`는 모표준편차(분모 n=10).
- `baseline_rss_kb`/`peak_rss_kb`/`avg_rss_kb`/`rss_supported`: Linux 전용, 그 외 빈 값/`false`.
- `target_os`: `macos`/`linux`/`windows`로 정규화(Rust 의미와 맞춤).

### Java 전용 진단 컬럼 (Rust엔 없음)

"Java가 Rust를 따라가려면 어디를 최적화해야 하나"의 직접 신호. 전부 처리량 pass에서 산출한다.

| 컬럼 | 의미 |
|---|---|
| `gc_count` | 처리량 pass 중 GC 수집 횟수 합 |
| `gc_time_ms` | 처리량 pass 중 GC 정지 시간 합(ms) — Java 처리량의 실제 비용 |
| `jit_compile_ms` | 처리량 pass 중 JIT 컴파일 시간(ms). ≈0이면 steady-state 도달의 방증 |
| `alloc_bytes` | **submit 구간** 할당 바이트(엔진+trade 할당 압력). 주문 생성 할당은 제외 |
| `heap_used_peak_kb` / `heap_used_avg_kb` | 적재 상태 힙 점유 peak/평균(`MemoryMXBean`) |

`alloc_bytes`는 throughput 타이밍과 동일한 submit 구간만 누적해 엔진/체결 할당 압력만 본다.
미지원 환경(할당 측정 미지원)에서는 빈 값이다.

## 해석 주의

- **메모리**: JVM의 RSS는 힙 예약·GC가 섞여 의미가 약하다. Java 메모리의 주신호는 RSS가 아니라
  `heap_used_*`(MXBean)다. RSS는 Rust 컬럼 parity용 best-effort다.
- **행 값의 byte 동일성은 비목표**다. 값은 머신마다 다르고 숫자 포맷도 다르다(Rust `1` vs Java 정수
  포맷). byte 동일성은 **헤더(컬럼 이름·순서)에 한정**되고, 그 이상은 컬럼 의미 일치로 충분하다.
- `jit_compile_ms`는 컴파일러가 비동기라 근사값이다.
