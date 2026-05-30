# 2026-05-19 Load Test Results

## Environment

- service: local `dev-start.sh --tmux` runtime
- app port: `8899`
- tool: Docker `justb4/jmeter:5.5`
- scope: cache-warmed query path and booking front-door path
- Sentinel perf profile:
  - query interface QPS: `10000`
  - query per-train QPS: `5000`
  - booking interface QPS: `1200`
  - booking per-train QPS: `300`
- booking inventory dataset:
  - load-test trains: `G1001`, `G1002`, `G305`, `D2201`
  - each train `available_stock` and Redis stock rebuilt to `20000` before the booking run

## Query Path

Artifacts:

- `load-test-runs/query-final-20260519/html-report/index.html`
- `load-test-runs/query-final-20260519/html-report/statistics.json`

Parameters:

- `50` users
- ramp `30s`
- duration `60s`

Results:

- total requests: `361,782`
- error rate: `0.00%`
- throughput: `6047.94 req/s`
- mean latency: `6.06 ms`
- `P95`: `17 ms`
- `P99`: `20 ms`
- max latency: `93 ms`

Service-side cache metrics after the run:

- requests: `361,782`
- caffeine hits: `361,782`
- redis hits: `0`
- db hits: `0`

Interpretation:

- after `/train/query/cache/preheat/all`, this round was fully absorbed by local cache
- this result is suitable for describing query-chain throughput after cache warmup

## Booking Smoke Path

Artifacts:

- `load-test-runs/book-frontdoor-clean-20260519/html-report/index.html`
- `load-test-runs/book-frontdoor-clean-20260519/html-report/statistics.json`

Parameters:

- `200` users
- ramp `20s`
- duration `20s`
- think time `0ms`
- train rotation: `G1001`, `G1002`, `G305`, `D2201`
- username prefix: `book_front_500_clean`

Results:

- total samples: `10,333`
- total error rate: `0.00%`
- total throughput: `515.46 req/s`
- total mean latency: `196.33 ms`
- total `P95`: `317 ms`
- total `P99`: `407.66 ms`

Book-ticket sub-transaction:

- samples: `10,133`
- error rate: `0.00%`
- throughput: `510.94 req/s`
- mean latency: `200.07 ms`
- `P95`: `318 ms`
- `P99`: `408 ms`

Correctness checks:

- immediate post-run reconciliation showed short-lived async lag on four booking trains, then converged back to `allConsistent=true`, `mismatchCount=0`
- log scan for `book_front_500_clean` batch found no `Sentinel` booking block warning

Interpretation:

- this round reflects front-door booking acceptance throughput under a dedicated performance profile and expanded local inventory dataset
- the metric is suitable for resume or report wording such as "multi-train booking front-door throughput"

## Notes

- do not mix this clean booking result with the earlier `book_front_500` run, which exceeded the lower `800/200` booking threshold and produced booking-side `Sentinel` warnings
- for booking, the correct validation sequence is: JMeter stats, log scan for `Sentinel` warnings, immediate reconciliation, delayed reconciliation after async drain
