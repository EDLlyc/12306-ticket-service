USE mysql_practice;

-- 1. 查询订单明细：订单号、乘客、车次、路线、状态、金额。
SELECT
    o.order_no,
    p.real_name,
    t.train_number,
    CONCAT(t.start_station, ' -> ', t.end_station) AS route,
    o.status,
    o.amount
FROM orders o
JOIN passengers p ON p.id = o.passenger_id
JOIN trains t ON t.id = o.train_id
ORDER BY o.created_at;

-- 2. 查询每个乘客的订单数量和总金额。
SELECT
    p.username,
    p.real_name,
    COUNT(o.id) AS order_count,
    COALESCE(SUM(o.amount), 0) AS total_amount
FROM passengers p
LEFT JOIN orders o ON o.passenger_id = p.id
GROUP BY p.id, p.username, p.real_name
ORDER BY total_amount DESC;

-- 3. 查询每趟车的已支付订单数量。
SELECT
    t.train_number,
    t.start_station,
    t.end_station,
    COUNT(o.id) AS paid_order_count
FROM trains t
LEFT JOIN orders o
    ON o.train_id = t.id
   AND o.status = 'PAID'
GROUP BY t.id, t.train_number, t.start_station, t.end_station
ORDER BY paid_order_count DESC;

-- 4. 查询支付金额超过 500 的乘客。
SELECT
    p.username,
    p.real_name,
    SUM(o.amount) AS paid_amount
FROM passengers p
JOIN orders o ON o.passenger_id = p.id
WHERE o.status = 'PAID'
GROUP BY p.id, p.username, p.real_name
HAVING SUM(o.amount) > 500;

-- 5. 查询没有订单的乘客。
SELECT p.id, p.username, p.real_name
FROM passengers p
LEFT JOIN orders o ON o.passenger_id = p.id
WHERE o.id IS NULL;

-- 6. 练习题：查询每条路线的订单数、已支付订单数、订单总金额。
