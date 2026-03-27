-- 测试退票功能的SQL脚本
USE db_12306;

-- 1. 查看当前订单状态
SELECT '当前订单状态:' AS info;
SELECT * FROM t_order WHERE order_sn = '927a205c-8d3d-420b-a652-b96980225c26';

-- 2. 执行退票操作（模拟cancelOrder工具的核心逻辑）
SELECT '执行退票操作...' AS action;
UPDATE t_order SET status = 'CANCELLED' WHERE order_sn = '927a205c-8d3d-420b-a652-b96980225c26';

-- 3. 回补库存（假设是G1001车次）
UPDATE t_train SET stock = stock + 1 WHERE train_number = 'G1001';

-- 4. 查看退票后的订单状态
SELECT '退票后订单状态:' AS info;
SELECT * FROM t_order WHERE order_sn = '927a205c-8d3d-420b-a652-b96980225c26';

-- 5. 查看车次库存
SELECT '车次库存信息:' AS info;
SELECT * FROM t_train WHERE train_number = 'G1001';
