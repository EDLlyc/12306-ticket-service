USE mysql_practice;

-- 1. 查看表结构和索引。
SHOW CREATE TABLE orders;
SHOW INDEX FROM orders;

-- 2. 查看查询计划，观察是否使用 idx_orders_train_status。
EXPLAIN
SELECT *
FROM orders
WHERE train_id = 4
  AND status = 'PAID';

-- 3. 查看查询计划，观察是否使用 idx_trains_route_time。
EXPLAIN
SELECT *
FROM trains
WHERE start_station = '合肥南'
  AND end_station = '上海虹桥'
ORDER BY departure_time;

-- 4. 事务回滚练习：执行后订单不会真正保留。
START TRANSACTION;

INSERT INTO orders (order_no, passenger_id, train_id, status, amount)
VALUES ('ROLLBACK_DEMO_001', 1, 5, 'PENDING', 117.50);

SELECT * FROM orders WHERE order_no = 'ROLLBACK_DEMO_001';

ROLLBACK;

SELECT * FROM orders WHERE order_no = 'ROLLBACK_DEMO_001';

-- 5. 行锁观察练习：
-- 开两个 MySQL 终端。在第一个终端执行下面三句，不要 COMMIT。
-- START TRANSACTION;
-- SELECT * FROM trains WHERE train_number = 'G305' FOR UPDATE;
-- UPDATE trains SET available_seats = available_seats - 1 WHERE train_number = 'G305';
--
-- 然后在第二个终端执行：
-- UPDATE trains SET available_seats = available_seats - 1 WHERE train_number = 'G305';
--
-- 第二个终端会等待第一个终端提交或回滚。

-- 6. 练习题：给 orders(created_at) 创建索引，再用 EXPLAIN 对比按时间范围查询的执行计划。
