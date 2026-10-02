-- GameStock Railway MySQL 8 performance indexes.
--
-- The backend creates these indexes idempotently during startup as well.
-- This file is a manual fallback for an existing Railway database when a
-- one-time SQL change is preferred. MySQL does not support CREATE INDEX IF
-- NOT EXISTS, so inspect the index list first and run only missing statements.

-- Select the Railway application database in the SQL console before running
-- the statements below. The script intentionally uses DATABASE() so it also
-- works when Railway names the database something other than gamestock.

-- Read-only preflight: capture the current table and index footprint.
SELECT table_name, table_rows,
       ROUND(data_length / 1048576, 2) AS data_mib,
       ROUND(index_length / 1048576, 2) AS index_mib
FROM information_schema.tables
WHERE table_schema = DATABASE()
ORDER BY data_length + index_length DESC;

SELECT table_name, index_name, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name IN ('orders', 'market_events', 'trades')
ORDER BY table_name, index_name, seq_in_index;

-- Run each missing CREATE INDEX statement separately in Railway's SQL console.
CREATE INDEX ix_orders_open_sequence
  ON orders (status, id);

CREATE INDEX ix_orders_stock_expiry
  ON orders (stock_id, status, expires_at, id);

CREATE INDEX ix_market_events_stock_type_time
  ON market_events (stock_id, event_type, published_at, created_at, id);

CREATE INDEX ix_market_events_stock_title
  ON market_events (stock_id, title);

CREATE INDEX ix_trades_buyer_fees
  ON trades (buyer_id, created_at, buyer_fee);

CREATE INDEX ix_trades_seller_fees
  ON trades (seller_id, created_at, seller_fee);

CREATE INDEX ix_trades_stock_sequence
  ON trades (stock_id, id);

-- Postflight: confirm the index names and column order.
SELECT table_name, index_name, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND index_name IN (
    'ix_orders_open_sequence', 'ix_orders_stock_expiry',
    'ix_market_events_stock_type_time', 'ix_market_events_stock_title',
    'ix_trades_buyer_fees', 'ix_trades_seller_fees',
    'ix_trades_stock_sequence'
  )
ORDER BY table_name, index_name, seq_in_index;
