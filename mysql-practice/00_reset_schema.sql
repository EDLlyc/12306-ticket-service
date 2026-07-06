SET NAMES utf8mb4;

DROP DATABASE IF EXISTS mysql_practice;
CREATE DATABASE mysql_practice
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE mysql_practice;

CREATE TABLE passengers (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL UNIQUE,
    real_name VARCHAR(50) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    member_level ENUM('NORMAL', 'SILVER', 'GOLD') NOT NULL DEFAULT 'NORMAL',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE trains (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    train_number VARCHAR(20) NOT NULL UNIQUE,
    start_station VARCHAR(50) NOT NULL,
    end_station VARCHAR(50) NOT NULL,
    departure_time DATETIME NOT NULL,
    arrival_time DATETIME NOT NULL,
    seat_count INT NOT NULL,
    available_seats INT NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    CHECK (seat_count >= 0),
    CHECK (available_seats >= 0),
    CHECK (available_seats <= seat_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE orders (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no VARCHAR(64) NOT NULL UNIQUE,
    passenger_id BIGINT NOT NULL,
    train_id BIGINT NOT NULL,
    seat_no VARCHAR(20) NULL,
    status ENUM('PENDING', 'PAID', 'CANCELLED', 'REFUNDED') NOT NULL DEFAULT 'PENDING',
    amount DECIMAL(10, 2) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at DATETIME NULL,
    CONSTRAINT fk_orders_passenger FOREIGN KEY (passenger_id) REFERENCES passengers(id),
    CONSTRAINT fk_orders_train FOREIGN KEY (train_id) REFERENCES trains(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_passengers_level ON passengers(member_level);
CREATE INDEX idx_trains_route_time ON trains(start_station, end_station, departure_time);
CREATE INDEX idx_orders_passenger_status ON orders(passenger_id, status);
CREATE INDEX idx_orders_train_status ON orders(train_id, status);

INSERT INTO passengers (username, real_name, phone, member_level, created_at) VALUES
('alice', '张三', '13800000001', 'GOLD', '2026-06-01 09:00:00'),
('bob', '李四', '13800000002', 'SILVER', '2026-06-02 10:00:00'),
('cindy', '王五', '13800000003', 'NORMAL', '2026-06-03 11:00:00'),
('david', '赵六', '13800000004', 'NORMAL', '2026-06-04 12:00:00'),
('emma', '钱七', '13800000005', 'GOLD', '2026-06-05 13:00:00');

INSERT INTO trains (train_number, start_station, end_station, departure_time, arrival_time, seat_count, available_seats, price) VALUES
('G1001', '北京南', '上海虹桥', '2026-07-10 08:00:00', '2026-07-10 12:30:00', 500, 496, 553.00),
('G1002', '上海虹桥', '北京南', '2026-07-10 14:00:00', '2026-07-10 18:30:00', 500, 498, 553.00),
('D2201', '合肥南', '北京南', '2026-07-11 07:30:00', '2026-07-11 11:50:00', 300, 299, 286.50),
('G305', '合肥南', '上海虹桥', '2026-07-11 09:15:00', '2026-07-11 12:45:00', 300, 297, 205.00),
('G7501', '南京南', '杭州东', '2026-07-12 10:00:00', '2026-07-12 11:30:00', 400, 400, 117.50);

INSERT INTO orders (order_no, passenger_id, train_id, seat_no, status, amount, created_at, paid_at) VALUES
('P202607100001', 1, 1, '03车08A', 'PAID', 553.00, '2026-07-01 09:10:00', '2026-07-01 09:12:00'),
('P202607100002', 2, 1, '03车08B', 'PAID', 553.00, '2026-07-01 09:20:00', '2026-07-01 09:22:00'),
('P202607100003', 3, 2, NULL, 'PENDING', 553.00, '2026-07-01 10:00:00', NULL),
('P202607100004', 1, 4, '05车12F', 'PAID', 205.00, '2026-07-01 10:30:00', '2026-07-01 10:31:00'),
('P202607100005', 4, 4, '05车13A', 'CANCELLED', 205.00, '2026-07-01 11:00:00', NULL),
('P202607100006', 5, 3, '01车02C', 'REFUNDED', 286.50, '2026-07-01 11:30:00', '2026-07-01 11:35:00'),
('P202607100007', 5, 4, '06车01A', 'PAID', 205.00, '2026-07-01 12:00:00', '2026-07-01 12:01:00');

SELECT 'mysql_practice reset complete' AS message;
