SET NAMES utf8mb4;

SET @add_available_stock = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE t_train ADD COLUMN available_stock INT NOT NULL DEFAULT 0 AFTER stock',
        'SELECT ''available_stock exists'''
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 't_train'
      AND COLUMN_NAME = 'available_stock'
);
PREPARE stmt FROM @add_available_stock;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_locked_stock = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE t_train ADD COLUMN locked_stock INT NOT NULL DEFAULT 0 AFTER available_stock',
        'SELECT ''locked_stock exists'''
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 't_train'
      AND COLUMN_NAME = 'locked_stock'
);
PREPARE stmt FROM @add_locked_stock;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_sold_stock = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE t_train ADD COLUMN sold_stock INT NOT NULL DEFAULT 0 AFTER locked_stock',
        'SELECT ''sold_stock exists'''
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 't_train'
      AND COLUMN_NAME = 'sold_stock'
);
PREPARE stmt FROM @add_sold_stock;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE t_train
SET available_stock = stock,
    locked_stock = 0,
    sold_stock = 0
WHERE available_stock = 0
  AND locked_stock = 0
  AND sold_stock = 0;

SELECT
    train_number,
    stock,
    available_stock,
    locked_stock,
    sold_stock
FROM t_train
ORDER BY train_number;
