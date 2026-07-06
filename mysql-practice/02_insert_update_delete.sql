USE mysql_practice;

-- 1. 新增一个乘客。
INSERT INTO passengers (username, real_name, phone, member_level)
VALUES ('frank', '孙八', '13800000006', 'NORMAL');

SELECT * FROM passengers WHERE username = 'frank';

-- 2. 修改乘客会员等级。
UPDATE passengers
SET member_level = 'SILVER'
WHERE username = 'frank';

SELECT username, real_name, member_level
FROM passengers
WHERE username = 'frank';

-- 3. 新增一个待支付订单，同时扣减余票。
START TRANSACTION;

INSERT INTO orders (order_no, passenger_id, train_id, status, amount)
SELECT 'P202607100008', p.id, t.id, 'PENDING', t.price
FROM passengers p
JOIN trains t ON t.train_number = 'G7501'
WHERE p.username = 'frank';

UPDATE trains
SET available_seats = available_seats - 1
WHERE train_number = 'G7501'
  AND available_seats > 0;

COMMIT;

SELECT order_no, status, amount FROM orders WHERE order_no = 'P202607100008';
SELECT train_number, available_seats FROM trains WHERE train_number = 'G7501';

-- 4. 取消刚才的订单，并归还余票。
START TRANSACTION;

UPDATE orders
SET status = 'CANCELLED'
WHERE order_no = 'P202607100008'
  AND status = 'PENDING';

UPDATE trains
SET available_seats = available_seats + 1
WHERE train_number = 'G7501';

COMMIT;

-- 5. 删除练习乘客。因为有订单外键引用，直接删除会失败。
-- DELETE FROM passengers WHERE username = 'frank';

-- 6. 练习题：新增一个乘客，给他买一张 G305，并把订单改成 PAID。
