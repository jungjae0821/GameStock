-- MySQL 초기 설정을 마친 뒤 사용할 GameStock 1차 스키마
CREATE DATABASE IF NOT EXISTS gamestock CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE gamestock;

CREATE TABLE users (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(128) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  nickname VARCHAR(50) NOT NULL UNIQUE,
  nickname_changed_at TIMESTAMP NULL,
  google_uid VARCHAR(128) NULL UNIQUE,
  email VARCHAR(255) NULL,
  profile_image_url VARCHAR(500) NULL,
  profile_completed BOOLEAN NOT NULL DEFAULT FALSE,
  role VARCHAR(20) NOT NULL DEFAULT 'USER',
  account_reset_at TIMESTAMP NULL,
  reset_used_at TIMESTAMP NULL,
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

CREATE TABLE mission_rewards (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  mission_id VARCHAR(40) NOT NULL,
  rewarded_on DATE NOT NULL,
  reward_cash BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_mission_reward_user_day (user_id, mission_id, rewarded_on),
  CONSTRAINT fk_mission_reward_user FOREIGN KEY (user_id) REFERENCES users(id)
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
  settled_quantity INT NOT NULL DEFAULT 0,
  average_price BIGINT NOT NULL DEFAULT 0,
  realized_profit_loss BIGINT NOT NULL DEFAULT 0,
  UNIQUE KEY uq_portfolio_user_stock (user_id, stock_id),
  CONSTRAINT fk_portfolios_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_portfolios_stock FOREIGN KEY (stock_id) REFERENCES stocks(id)
);

CREATE TABLE market_events (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  stock_id BIGINT NULL,
  event_type VARCHAR(40) NOT NULL,
  source VARCHAR(40) NOT NULL DEFAULT '미디어 보도',
  title VARCHAR(150) NOT NULL,
  description TEXT,
  impact DECIMAL(6,2) NOT NULL,
  published_at TIMESTAMP NULL,
  price_at_publish BIGINT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_events_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
  INDEX ix_market_events_stock_type_time (stock_id, event_type, published_at, created_at, id),
  INDEX ix_market_events_stock_title (stock_id, title)
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
  CONSTRAINT fk_orders_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
  INDEX ix_orders_live_book (stock_id, status, side, price, created_at, id),
  INDEX ix_orders_expiry (status, expires_at),
  INDEX ix_orders_user_live (user_id, status, stock_id),
  INDEX ix_orders_open_sequence (status, id),
  INDEX ix_orders_stock_expiry (stock_id, status, expires_at, id)
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
  buyer_fee BIGINT NOT NULL DEFAULT 0,
  seller_fee BIGINT NOT NULL DEFAULT 0,
  fee BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_trades_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
  CONSTRAINT fk_trades_buy_order FOREIGN KEY (buy_order_id) REFERENCES orders(id),
  CONSTRAINT fk_trades_sell_order FOREIGN KEY (sell_order_id) REFERENCES orders(id),
  CONSTRAINT fk_trades_buyer FOREIGN KEY (buyer_id) REFERENCES users(id),
  CONSTRAINT fk_trades_seller FOREIGN KEY (seller_id) REFERENCES users(id),
  INDEX ix_trades_stock_time (stock_id, created_at),
  INDEX ix_trades_taker (taker_order_id),
  INDEX ix_trades_maker (maker_order_id),
  INDEX ix_trades_buyer_fees (buyer_id, created_at, buyer_fee),
  INDEX ix_trades_seller_fees (seller_id, created_at, seller_fee),
  INDEX ix_trades_stock_sequence (stock_id, id)
);

-- 체결 원장. 현재는 체결 즉시 SETTLED로 반영하며, 이전 버전의 PENDING 행도
-- 서버 시작 시 즉시 정산한다.
CREATE TABLE settlements (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  trade_id BIGINT NOT NULL UNIQUE,
  buyer_id BIGINT NOT NULL,
  seller_id BIGINT NOT NULL,
  stock_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  gross_amount BIGINT NOT NULL,
  buyer_fee BIGINT NOT NULL DEFAULT 0,
  seller_fee BIGINT NOT NULL DEFAULT 0,
  buyer_quantity_before INT NULL,
  buyer_settled_quantity_before INT NULL,
  buyer_average_price_before BIGINT NULL,
  buyer_realized_profit_loss_before BIGINT NULL,
  seller_quantity_before INT NULL,
  seller_settled_quantity_before INT NULL,
  seller_average_price_before BIGINT NULL,
  seller_realized_profit_loss_before BIGINT NULL,
  settlement_at TIMESTAMP NOT NULL,
  status ENUM('PENDING', 'SETTLED', 'CANCELLED') NOT NULL DEFAULT 'PENDING',
  settled_at TIMESTAMP NULL,
  cancelled_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_settlements_trade FOREIGN KEY (trade_id) REFERENCES trades(id),
  CONSTRAINT fk_settlements_buyer FOREIGN KEY (buyer_id) REFERENCES users(id),
  CONSTRAINT fk_settlements_seller FOREIGN KEY (seller_id) REFERENCES users(id),
  CONSTRAINT fk_settlements_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
  INDEX ix_settlements_pending (status, settlement_at),
  INDEX ix_settlements_buyer (buyer_id, status),
  INDEX ix_settlements_seller (seller_id, status)
);

-- 체결이 발생한 날짜별 시가(자정 기준), 종가(마지막 체결가), 거래량 집계
CREATE TABLE daily_market_summaries (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  stock_id BIGINT NOT NULL,
  trading_date DATE NOT NULL,
  open_price BIGINT NOT NULL,
  close_price BIGINT NOT NULL,
  total_volume BIGINT NOT NULL DEFAULT 0,
  UNIQUE KEY uq_daily_summary_stock_date (stock_id, trading_date),
  CONSTRAINT fk_daily_summary_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
  INDEX ix_daily_summary_date (trading_date)
);

-- 종목 상세 화면의 개인 기능(웹·모바일 공용)
CREATE TABLE user_watchlists (
  user_id BIGINT NOT NULL,
  stock_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, stock_id),
  CONSTRAINT fk_watchlist_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_watchlist_stock FOREIGN KEY (stock_id) REFERENCES stocks(id)
);

CREATE TABLE price_alerts (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  stock_id BIGINT NOT NULL,
  target_price BIGINT NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  triggered_at TIMESTAMP NULL,
  CONSTRAINT fk_alert_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_alert_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
  INDEX ix_alert_user_active (user_id, active)
);

CREATE TABLE stock_comments (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  stock_id BIGINT NOT NULL,
  comment_text VARCHAR(240) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_comment_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_comment_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
  INDEX ix_comment_stock_time (stock_id, created_at)
);

CREATE TABLE stock_tags (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  stock_id BIGINT NOT NULL,
  tag VARCHAR(40) NOT NULL,
  created_by BIGINT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_stock_tag (stock_id, tag),
  CONSTRAINT fk_tag_stock FOREIGN KEY (stock_id) REFERENCES stocks(id),
  CONSTRAINT fk_tag_user FOREIGN KEY (created_by) REFERENCES users(id)
);

-- 시뮬레이션 시드를 고정해 동일한 DB 상태에서 봇 흐름을 재현할 수 있도록 한다.
CREATE TABLE simulation_state (
  id TINYINT PRIMARY KEY,
  tick BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE news_price_state (
  stock_id BIGINT PRIMARY KEY,
  applied_bias DECIMAL(8,6) NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_news_price_state_stock FOREIGN KEY (stock_id) REFERENCES stocks(id)
);

-- 여러 백엔드 인스턴스가 떠도 주문 매칭 순서를 하나씩 처리하기 위한 DB 행 잠금
CREATE TABLE market_locks (
  id TINYINT PRIMARY KEY
);

-- 한국시간 거래일별 VI·서킷브레이커 상태를 서버 재시작 후에도 유지한다.
CREATE TABLE market_protection_state (
  id INT PRIMARY KEY,
  trading_date DATE NOT NULL,
  phase VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
  level INT NOT NULL DEFAULT 0,
  trigger_index DOUBLE NOT NULL DEFAULT 1000,
  below_since TIMESTAMP NULL,
  started_at TIMESTAMP NULL,
  ends_at TIMESTAMP NULL
);

CREATE TABLE stock_protection_state (
  stock_id BIGINT PRIMARY KEY,
  day_reference BIGINT NOT NULL,
  static_reference BIGINT NOT NULL,
  dynamic_reference BIGINT NOT NULL,
  last_trade_price BIGINT NOT NULL,
  vi_type VARCHAR(24) NULL,
  started_at TIMESTAMP NULL,
  ends_at TIMESTAMP NULL,
  CONSTRAINT fk_protection_stock FOREIGN KEY (stock_id) REFERENCES stocks(id) ON DELETE CASCADE
);

CREATE TABLE trading_restriction_events (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  stock_id BIGINT NULL,
  kind VARCHAR(24) NOT NULL,
  level INT NOT NULL DEFAULT 0,
  reference_price DOUBLE NOT NULL,
  trigger_price DOUBLE NOT NULL,
  started_at TIMESTAMP NOT NULL,
  ends_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_restriction_event_stock FOREIGN KEY (stock_id) REFERENCES stocks(id) ON DELETE CASCADE
);

-- 칭호. 일반 칭호는 1회 지급, 주간 수익률 칭호는 win_count로 n관왕을 센다.
CREATE TABLE user_titles (
  user_id BIGINT NOT NULL,
  title_id VARCHAR(40) NOT NULL,
  win_count INT NOT NULL DEFAULT 1,
  notified BOOLEAN NOT NULL DEFAULT FALSE,
  acquired_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, title_id),
  CONSTRAINT fk_user_title_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE weekly_return_weeks (
  week_start DATE NOT NULL PRIMARY KEY,
  snapshot_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  awarded_at TIMESTAMP NULL
);

CREATE TABLE weekly_return_snapshots (
  week_start DATE NOT NULL,
  user_id BIGINT NOT NULL,
  baseline_asset BIGINT NOT NULL,
  PRIMARY KEY (week_start, user_id),
  CONSTRAINT fk_weekly_snapshot_user FOREIGN KEY (user_id) REFERENCES users(id)
);
