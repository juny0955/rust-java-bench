# Rust 엔진 기준선

측정일: 2026-06-20
실행 환경: 루트 [`README.md`](../../README.md#실행-환경) 기준 (OCI, aarch64 Neoverse-N1, 2 vCPU, Ubuntu 24.04.4 LTS, 커널 6.17.0-1016-oracle)
실행 명령: `cargo run --release --bin bench_runner` (`rust-engine/`)
측정 방식/CSV 컬럼 정의: [`rust-engine/benches/README.md`](../../rust-engine/benches/README.md)

> 기존 `WorstCaseCross`를 `DeepSweepCross`로 이름 변경하고, 가격 레벨 트리가 끝없이 커지는
> memory-pressure worst case `BookGrowthWorst`를 새로 추가한 뒤 재측정한 결과다. `ThinBook`/
> `ActiveFill`/`DeepSweepCross`(구 `WorstCaseCross`)는 이전 측정과 다른 인스턴스 실행이라 ±1~2%
> 노이즈가 있을 뿐 시나리오 자체는 변경되지 않았다.

## 요약 (mean ± stddev, n=10)

| 시나리오 | 스케일 | ops/s | p50 | p90 | p99 | p999 | max | peak RSS | avg RSS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| ThinBook | 1,000,000 | 7,110,043 ± 95,498 | 120ns | 164ns ± 12ns | 568ns ± 47ns | 6,928ns ± 244ns | 116,421ns ± 168,516ns | 71,262KB ± 438KB | 43,583KB ± 75KB |
| ThinBook | 10,000,000 | 7,001,214 ± 315,194 | 120ns | 172ns ± 18ns | 400ns ± 134ns | 4,436ns ± 581ns | 458,742ns ± 962,880ns | 690,280KB ± 1,640KB | 335,786KB ± 667KB |
| ActiveFill | 1,000,000 | 13,779,164 ± 48,251 | 80ns | 160ns | 240ns | 1,040ns ± 228ns | 223,381ns ± 292,624ns | 20,116KB ± 1,407KB | 12,850KB ± 854KB |
| ActiveFill | 10,000,000 | 14,109,073 ± 21,004 | 80ns | 160ns | 240ns | 1,176ns ± 27ns | 102,761ns ± 221,087ns | 104,960KB ± 57KB | 56,479KB ± 386KB |
| DeepSweepCross | 1,000,000 | 3,859,277 ± 11,116 | 120ns | 200ns | 280ns | 51,285ns ± 29,069ns | 182,425ns ± 148,757ns | 6,831KB ± 4KB | 6,359KB ± 4KB |
| DeepSweepCross | 10,000,000 | 3,857,726 ± 9,552 | 120ns | 200ns | 280ns | 29,056ns ± 6,659ns | 132,493ns ± 4,878ns | 6,859KB ± 6KB | 6,798KB ± 6KB |
| BookGrowthWorst | 1,000,000 | 5,231,770 ± 361,535 | 120ns | 280ns | 560ns | 924ns ± 38ns | 154,469ns ± 232,867ns | 287,373KB ± 6KB | 160,510KB ± 6KB |
| BookGrowthWorst | 10,000,000 | 4,871,033 ± 301,259 | 120ns | 280ns | 596ns ± 55ns | 1,244ns ± 364ns | 33,232ns ± 9,248ns | 2,815,277KB ± 6KB | 1,424,884KB ± 6KB |

원본 CSV: [`raw.csv`](./raw.csv) (측정 인스턴스의 `rust-engine/target/bench-results/1781959710.csv`에서 가져옴)

## 관찰

### 기존 시나리오는 재측정에도 안정적이다
- `ThinBook`/`ActiveFill`/`DeepSweepCross`의 ops/s가 이전 측정(이름 변경 전 `WorstCaseCross`) 대비
  ±2% 이내로 일치한다(예: ThinBook 1M 7,219,060→7,110,043, DeepSweepCross 1M 3,897,419→3,859,277).
  이름 변경이 알고리즘을 바꾸지 않았다는 것을 수치로도 확인.

### BookGrowthWorst가 의도한 메모리 worst case를 정확히 드러낸다
- `BookGrowthWorst`의 peak RSS가 같은 스케일에서 다른 어떤 시나리오보다 압도적으로 크다
  (10M 기준 **2,815,277KB(≈2.7GB)** vs `ThinBook` 690,280KB, `ActiveFill` 104,960KB,
  `DeepSweepCross` 6,859KB — ThinBook의 약 **4.1배**). `ThinBook`은 bounded range라 가격 레벨
  개수가 스케일과 무관하게 수렴하지만, `BookGrowthWorst`는 주문마다 **새로운 고유 가격 레벨**을
  만들어 트리가 주문 수에 정비례해 끝없이 커진다는 차이가 RSS에 그대로 나타난다.
- 처리량은 `ActiveFill > ThinBook > BookGrowthWorst > DeepSweepCross` 순. `BookGrowthWorst`는
  체결 검사 없이 항상 새 리프를 삽입만 하므로 `ThinBook`보다는 느리지만(트리가 계속 커져 삽입
  비용이 누적됨) 매 cycle마다 1,000개 레벨을 sweep하는 `DeepSweepCross`보다는 빠르다.
- `BookGrowthWorst`의 p999/max는 다른 worst-case(`DeepSweepCross`)보다 훨씬 낮고 안정적이다
  (1M p999 924ns vs DeepSweepCross 51,285ns). 단순 삽입은 매칭 루프 반복이 없어 꼬리 레이턴시를
  유발하지 않는다 — "처리량/메모리 worst case"와 "꼬리 레이턴시 worst case"가 서로 다른
  시나리오라는 점을 보여준다.
- `BookGrowthWorst`의 `run_0`는 이후 run들보다 ops/s가 약 20% 낮다(원본 CSV: 1M run_0=4,148,708
  vs run_1~9 평균 ~5,350,000; 10M run_0=3,969,710 vs run_1~9 평균 ~4,968,000). 트리·메모리
  할당자가 처음 그 크기로 커지는 비용(첫 페이지 폴트·malloc arena 확장)이 run_0에 몰리는 것으로
  보이며, 이후 run은 같은 크기로 재할당되며 캐시된 메모리를 재사용해 빨라진다.

### 기존 worst case(DeepSweepCross)와 새 worst case(BookGrowthWorst)는 서로 다른 비용을 보여준다
- `DeepSweepCross`는 RSS가 1M/10M 모두 ~6.8MB로 거의 동일(결정적으로 호가창 깊이가 항상
  `SWEEP_LEVELS`(1,000)에서 평형). 반면 `BookGrowthWorst`는 RSS가 스케일에 거의 선형으로 증가
  (1M 287MB → 10M 2.8GB, 약 9.8배). 두 시나리오를 분리하지 않았다면 "worst case"라는 이름 하나로
  매칭 루프 비용과 메모리 증가 비용이 뭉뚱그려졌을 것이다.

### 안정성
- `ThinBook`/`ActiveFill`/`DeepSweepCross`는 ops_sec stddev가 평균의 0.1~4.5% 수준으로 안정적이다.
  `BookGrowthWorst`는 run_0의 워밍업 효과 때문에 stddev가 6~7%로 다소 크지만, run_1 이후만 보면
  변동이 1% 미만이다.

## 참고

- 본 결과는 Java 개선 이력의 기준선이다. Java 결과는 [`../java/`](../java/) 아래에 버전별로 누적한다.
  `BookGrowthWorst`는 아직 Java 측 측정이 없어 `../java/` 비교표에는 포함되지 않았다.
