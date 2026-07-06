USE mysql_practice;

-- 1. 查看所有乘客。
SELECT * FROM passengers;

-- 2. 只查询乘客姓名、手机号、会员等级。
SELECT real_name, phone, member_level
FROM passengers;

-- 3. 查询 GOLD 会员。
SELECT id, username, real_name
FROM passengers
WHERE member_level = 'GOLD';

-- 4. 查询北京南到上海虹桥的车次，按出发时间升序。
SELECT train_number, start_station, end_station, departure_time, price
FROM trains
WHERE start_station = '北京南'
  AND end_station = '上海虹桥'
ORDER BY departure_time ASC;

-- 5. 查询票价在 200 到 600 之间的车次。
SELECT train_number, price
FROM trains
WHERE price BETWEEN 200 AND 600
ORDER BY price DESC;

-- 6. 模糊查询 G 字头车次。
SELECT train_number, departure_time
FROM trains
WHERE train_number LIKE 'G%';

-- 7. 查询最近创建的 3 个订单。
SELECT order_no, status, amount, created_at
FROM orders
ORDER BY created_at DESC
LIMIT 3;

-- 8. 练习题：查询余票少于 300 的车次，只返回车次、路线、余票。
