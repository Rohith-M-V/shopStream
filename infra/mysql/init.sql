-- One schema per service. No cross-service joins, ever.
CREATE DATABASE IF NOT EXISTS auth_db         CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS inventory_db    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS order_db        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS payment_db      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS notification_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'shop'@'%' IDENTIFIED BY 'shop';
GRANT ALL PRIVILEGES ON auth_db.*         TO 'shop'@'%';
GRANT ALL PRIVILEGES ON inventory_db.*    TO 'shop'@'%';
GRANT ALL PRIVILEGES ON order_db.*        TO 'shop'@'%';
GRANT ALL PRIVILEGES ON payment_db.*      TO 'shop'@'%';
GRANT ALL PRIVILEGES ON notification_db.* TO 'shop'@'%';
FLUSH PRIVILEGES;
