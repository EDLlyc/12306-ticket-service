# 12306 Ticket Service Load Test Plan

## 1. Why This Project Must Be Load Tested

This project is not a plain CRUD service. The booking path already contains concurrency-specific controls:

- `Redis + Lua` for atomic stock reservation
- segmented stock buckets in Redis
- `RocketMQ` for async order persistence and timeout closing
- `Sentinel` for interface and hot-train throttling
- `Caffeine + Redis + BloomFilter` on the query side

Without load testing, these designs are only implementation claims. The real questions are still unanswered:

- Does booking oversell under concurrent access?
- Does Redis stock remain consistent with MySQL and order states?
- Do Sentinel thresholds protect the service or reject too much traffic?
- Can MQ consumers drain backlog in a bounded time after a spike?
- Does mixed traffic make query requests starve booking requests or the opposite?

## 2. Goals

Primary goals:

- verify no oversell on hot trains
- verify Redis/MySQL/order-state consistency after pressure
- find safe throughput for query and booking
- validate throttling and degradation behavior
- measure backlog recovery time for async consumers

Non-goals for the first round:

- RAG chain load testing
- browser/UI performance
- cross-region or WAN performance

## 3. Test Scope

Core endpoints:

- `GET /train/query`
- `POST /train/book/lua`
- `GET /user/orders`
- `POST /user/pay`
- `POST /user/refund`
- `GET /train/init`
- `GET /audit/reconcile/stock/{trainNumber}`

Relevant system components:

- Spring Boot / Tomcat
- MySQL
- Redis
- RocketMQ
- Sentinel rules

## 4. Environment Requirements

Use a stable environment close to the local compose stack already used by the repo.

Required before each run:

- same application version and config for all repeated runs
- clean or known-order-state database
- Redis and RocketMQ fully started
- target train stock preheated through `/train/init`
- test users isolated from manual demo users

Recommended runtime settings to record with every report:

- Git commit id
- `application.yml` or env override values
- Tomcat thread settings
- Sentinel QPS settings
- stock bucket count
- whether MQ consumers are enabled

## 5. Success Criteria

The first workable acceptance bar should be explicit.

For `GET /train/query`:

- error rate `< 0.1%`
- `P95 <= 200ms`
- `P99 <= 500ms`

For `POST /train/book/lua` front-door acceptance:

- HTTP error rate `< 1%`
- `P95 <= 500ms`
- `P99 <= 1000ms`
- no `401` due to token handling mistakes in the test script

For booking end-to-end:

- no oversell
- no negative Redis stock
- no reconciliation mismatch after backlog drains
- MQ backlog drains within `5 minutes` after pressure stops

For protection behavior:

- throttling is observable and bounded
- throttled traffic does not cause DB stock corruption

## 6. Metrics To Collect

Application:

- QPS, avg, `P95`, `P99`, max latency
- HTTP status counts
- Sentinel block counts
- thread pool usage
- GC pause time

Business:

- accepted booking count
- order status counts: `CREATED`, `PROCESSING`, `PENDING`, `SUCCESS`, `FAILED`, `CANCELLED`
- stock reconciliation result from `/audit/reconcile/stock/{trainNumber}`

Infrastructure:

- Redis CPU and memory
- MySQL CPU, active connections, slow SQL
- RocketMQ topic backlog and consumer lag
- host CPU, memory, load average, disk IO

## 7. Test Data Strategy

Use at least three train categories:

- hot train: one train hit by most traffic, for example `G1`
- warm train: several trains with medium traffic
- cold train: long-tail trains from `train-numbers.csv`

Recommended stock presets:

- smoke: `100`
- baseline booking: `2000`
- spike booking: `5000+`

User strategy:

- one logical username per JMeter thread
- avoid shared usernames to reduce artificial order contention

## 8. Test Scenarios

### Scenario A: Smoke

Purpose:

- verify the environment, script, token flow, and basic observability

Plan:

- `20` users
- ramp `30s`
- duration `5m`
- query and booking each run once separately

Exit criteria:

- no auth failures
- no mass `5xx`
- metrics and logs visible

### Scenario B: Query Baseline

Purpose:

- measure steady-state query throughput

Plan:

- use existing `ticket-query-load-test.jmx`
- `200`, `500`, `1000`, `2000` users step test
- `10m` hold for each level

Focus:

- local cache hit behavior
- Redis hit ratio
- DB fallback frequency

### Scenario C: Booking Front-Door Throughput

Purpose:

- measure the synchronous part of booking: auth, Sentinel, transactional message acceptance

Plan:

- use `ticket-book-load-test.jmx`
- single hot train first
- `50`, `100`, `200`, `400` users
- `10m` hold for each level

Pass conditions:

- response latency remains bounded
- no `401`
- limited throttling until expected threshold

### Scenario D: Booking End-To-End Consistency

Purpose:

- verify async flow correctness under load

Plan:

- preheat one train with known stock
- run booking load until at least `70%` of stock is reserved or sold
- stop pressure
- wait for consumers to drain
- run `/audit/reconcile/stock/{trainNumber}`

Must verify:

- no oversell
- `redisStock == expectedRedisStock`
- `bucketSumStock == expectedRedisStock`

### Scenario E: Mixed Traffic

Purpose:

- simulate realistic production skew where query traffic dominates booking traffic

Plan:

- query:booking ratio `20:1`
- use `ticket-mixed-load-test.jmx`
- hot-train and long-tail mix
- `15m` hold

Must verify:

- query traffic does not starve booking acceptance
- booking traffic does not collapse query latency

### Scenario F: Sold-Out and Protection Behavior

Purpose:

- verify the system fails safely when stock is exhausted or rate-limited

Plan:

- small stock on a hot train
- high booking concurrency

Expected:

- sold-out responses increase
- no negative stock
- no reconciliation mismatch

## 9. Recommended Execution Order

1. smoke
2. query baseline
3. booking front-door throughput
4. booking end-to-end consistency
5. mixed traffic
6. sold-out and protection behavior

Do not start with the mixed scenario. If smoke and single-path tests are not stable, mixed traffic only hides the root cause.

## 10. JMeter Assets In This Repo

Existing:

- `ticket-query-load-test.jmx`

Added in this change:

- `ticket-book-load-test.jmx`
- `ticket-mixed-load-test.jmx`

Suggested CLI examples:

```bash
jmeter -n -t ticket-query-load-test.jmx \
  -Jhost=127.0.0.1 -Jport=8899 -Jusers=1000 -Jramp=120 -Jduration=600 \
  -l result-query.jtl -e -o html-report-query
```

```bash
jmeter -n -t ticket-book-load-test.jmx \
  -Jhost=127.0.0.1 -Jport=8899 -JtrainNumber=G1 -Jusers=200 -Jramp=60 -Jduration=600 \
  -JusernamePrefix=load_user -l result-book.jtl -e -o html-report-book
```

```bash
jmeter -n -t ticket-mixed-load-test.jmx \
  -Jhost=127.0.0.1 -Jport=8899 -JtrainNumber=G1 \
  -JqueryUsers=1000 -JbookUsers=50 -Jramp=120 -Jduration=900 \
  -JusernamePrefix=load_user -l result-mixed.jtl -e -o html-report-mixed
```

## 11. Post-Run Checks

Always run these after booking scenarios:

1. check `/audit/reconcile/stock/{trainNumber}`
2. inspect order status distribution in MySQL
3. inspect RocketMQ backlog until drained
4. inspect application logs for repeated consumer retries
5. record Sentinel block events and total throttled count

If reconciliation is not clean after backlog drain, treat the run as failed even if HTTP metrics look good.

## 12. Known Risks In The Current Project

- current repo had query load assets but no dedicated booking JMeter plan
- booking is async, so front-door latency alone is not enough
- query and booking Sentinel defaults are low enough that naive tests may mostly measure throttling
- sold-out runs can look healthy at the HTTP layer while leaving reconciliation drift behind

## 13. Deliverables Per Test Round

For each test round, keep:

- JMeter `.jtl`
- HTML report
- environment config snapshot
- reconciliation result JSON
- short conclusion: bottleneck, safe threshold, next action

## 14. Immediate Next Step

Run this exact sequence first:

1. `GET /train/init?trainNumber=G1`
2. smoke on `ticket-book-load-test.jmx`
3. `200`-user booking front-door test for `10m`
4. `/audit/reconcile/stock/G1`

That gives the first meaningful signal with low setup cost.
