-- Read-only diagnostics. Select the GameStock database before running.
-- Compare these estimates with the Railway MySQL volume's usage and limit.
-- The table named by ERROR 1114 may only be the next table that tried to grow.

SELECT DATABASE() AS selected_database;

SELECT table_name, engine, table_rows,
       ROUND(data_length / 1048576, 2) AS data_mib,
       ROUND(index_length / 1048576, 2) AS index_mib,
       ROUND(data_free / 1048576, 2) AS internally_free_mib
FROM information_schema.tables
WHERE table_schema = DATABASE()
ORDER BY data_length + index_length DESC;

SELECT COUNT(*) AS receipt_rows,
       COALESCE(SUM(id LIKE 'bot:%'), 0) AS reusable_bot_slots,
       ROUND(COALESCE(SUM(OCTET_LENGTH(payload)), 0) / 1048576, 2) AS payload_mib,
       MIN(created_at) AS oldest_receipt,
       MAX(created_at) AS newest_receipt
FROM market_batch_receipts;

SHOW VARIABLES WHERE Variable_name IN
    ('innodb_file_per_table', 'log_bin', 'binlog_expire_logs_seconds',
     'max_binlog_size', 'innodb_redo_log_capacity');

-- Binary logs and other MySQL files also consume the volume. This read-only
-- statement may need extra privileges and fails if binary logging is disabled.
SHOW BINARY LOGS;

-- Do not automatically truncate receipts: active batches may still need them
-- to resolve a lost COMMIT response. Recover space with writers stopped and
-- only after identifying what occupies the volume. Preserve trades, balances,
-- positions and order history. DELETE may make space reusable inside a table
-- without shrinking its file or increasing Railway's displayed free space.
