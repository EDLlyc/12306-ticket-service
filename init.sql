DROP TABLE IF EXISTS t_order;
DROP TABLE IF EXISTS t_train;

CREATE TABLE t_train (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    train_number VARCHAR(20) NOT NULL,
    start_station VARCHAR(50) NOT NULL,
    end_station VARCHAR(50) NOT NULL,
    start_time DATETIME NOT NULL,
    end_time DATETIME NOT NULL,
    stock INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE t_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_sn VARCHAR(64) NOT NULL UNIQUE,
    train_number VARCHAR(20) NOT NULL,
    username VARCHAR(50) NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO t_train (train_number, start_station, end_station, start_time, end_time, stock) VALUES
('G1001', '北京南', '上海虹桥', '2026-03-25 08:00:00', '2026-03-25 12:30:00', 500),
('G1002', '上海虹桥', '北京南', '2026-03-25 14:00:00', '2026-03-25 18:30:00', 300),
('G305',  '合肥南', '上海虹桥', '2026-03-25 09:15:00', '2026-03-25 12:45:00', 200),
('D2201', '合肥南', '北京南', '2026-03-25 07:30:00', '2026-03-25 11:50:00', 150),
('G7501', '南京南', '杭州东', '2026-03-25 10:00:00', '2026-03-25 11:30:00', 400);
