-- MySQL 초기 설정을 마친 뒤 사용할 GameStock 1차 스키마
CREATE DATABASE IF NOT EXISTS gamestock CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE gamestock;

CREATE TABLE users (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(128) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  nickname VARCHAR(50) NOT NULL UNIQUE,
  google_uid VARCHAR(128) NULL UNIQUE,
  email VARCHAR(255) NULL,
  profile_image_url VARCHAR(500) NULL,
  profile_completed BOOLEAN NOT NULL DEFAULT FALSE,
  cash BIGINT NOT NULL DEFAULT 1000000,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE attendance_rewards (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  rewarded_on DATE NOT NULL,
  streak_day INT NOT NULL,
  reward_cash BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_attendance_user_day (user_id, rewarded_on),
  CONSTRAINT fk_attendance_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE games (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  developer VARCHAR(100) NOT NULL,
  genre VARCHAR(50) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE stocks (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  game_id BIGINT NOT NULL,
  stock_code VARCHAR(12) NOT NULL UNIQUE,
  current_price BIGINT NOT NULL,
  previous_price BIGINT NOT NULL,
  total_volume BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_stocks_game FOREIGN KEY (game_id) REFERENCES games(id)
);

CREATE TABLE stock_price_history (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  stock_id BIGINT NOT NULL,
  price BIGINT NOT NULL,
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_price_history_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
  INDEX ix_price_history_stock_time (stock_id, recorded_at)
);

CREATE TABLE portfolios (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  stock_id BIGINT NOT NULL,
  quantity INT NOT NULL DEFAULT 0,
  average_price BIGINT NOT NULL DEFAULT 0,
  UNIQUE KEY uq_portfolio_user_stock (user_id, stock_id),
  CONSTRAINT fk_portfolios_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_portfolios_stock FOREIGN KEY (stock_id) REFERENCES stocks(id)
);

CREATE TABLE market_events (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  stock_id BIGINT NULL,
  event_type VARCHAR(40) NOT NULL,
  title VARCHAR(150) NOT NULL,
  description TEXT,
  impact DECIMAL(6,2) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_events_stock FOREIGN KEY (stock_id) REFERENCES stocks(id)
);

CREATE TABLE orders (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  stock_id BIGINT NOT NULL,
  side ENUM('BUY', 'SELL') NOT NULL,
  order_type ENUM('MARKET', 'LIMIT') NOT NULL DEFAULT 'MARKET',
  price BIGINT NULL,
  quantity INT NOT NULL,
  remaining_quantity INT NOT NULL,
  status ENUM('OPEN', 'FILLED', 'PARTIAL', 'CANCELLED') NOT NULL DEFAULT 'OPEN',
  reserved_cash BIGINT NOT NULL DEFAULT 0,
  reserved_quantity INT NOT NULL DEFAULT 0,
  expires_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_orders_stock FOREIGN KEY (stock_id) REFERENCES stocks(id)
);

CREATE TABLE trades (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  stock_id BIGINT NOT NULL,
  buy_order_id BIGINT NOT NULL,
  sell_order_id BIGINT NOT NULL,
  buyer_id BIGINT NOT NULL,
  seller_id BIGINT NOT NULL,
  maker_order_id BIGINT NOT NULL,
  taker_order_id BIGINT NOT NULL,
  aggressor_side ENUM('BUY', 'SELL') NOT NULL,
  quantity INT NOT NULL,
  price BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_trades_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
  CONSTRAINT fk_trades_buy_order FOREIGN KEY (buy_order_id) REFERENCES orders(id),
  CONSTRAINT fk_trades_sell_order FOREIGN KEY (sell_order_id) REFERENCES orders(id),
  CONSTRAINT fk_trades_buyer FOREIGN KEY (buyer_id) REFERENCES users(id),
  CONSTRAINT fk_trades_seller FOREIGN KEY (seller_id) REFERENCES users(id),
  INDEX ix_trades_stock_time (stock_id, created_at),
  INDEX ix_trades_taker (taker_order_id),
  INDEX ix_trades_maker (maker_order_id)
);
