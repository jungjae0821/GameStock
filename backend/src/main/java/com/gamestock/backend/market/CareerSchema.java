package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tables for the prop desk career and the personal wallet.
 *
 * <p>The first time {@code career_state} is created, every existing account gets a row
 * marked {@code legacy}. Legacy accounts keep the old open access until the one-off
 * migration ({@link CareerMigrationService}) moves them to trainee. Accounts created
 * later get a trainee row on first use.
 *
 * <p>{@code attendance_rewards.reward_cash} and {@code mission_rewards.reward_cash} keep
 * meaning "cash paid into the trading account", which return calculations subtract.
 * Rewards paid to the personal wallet, and old rewards whose trading cash the migration
 * reset, are kept in {@code wallet_cash} instead so those calculations stay right.
 */
final class CareerSchema {
    private CareerSchema() { }

    static void ensureTables(JdbcTemplate jdbc) {
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='career_state'",
                Integer.class);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS career_state (
                  user_id BIGINT NOT NULL PRIMARY KEY,
                  grade VARCHAR(16) NOT NULL DEFAULT 'TRAINEE',
                  grade_since TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  grade_base BIGINT NOT NULL,
                  outside_cash BIGINT NOT NULL DEFAULT 0,
                  return_streak INT NOT NULL DEFAULT 0,
                  high_water_mark BIGINT NOT NULL,
                  personal_cash BIGINT NOT NULL DEFAULT 0,
                  ojt_steps VARCHAR(64) NOT NULL DEFAULT '',
                  day_open_on DATE NULL,
                  day_open_asset BIGINT NULL,
                  day_locked_until TIMESTAMP(3) NULL,
                  lock_reason VARCHAR(16) NULL,
                  breach_count INT NOT NULL DEFAULT 0,
                  last_settled_on DATE NULL,
                  legacy BOOLEAN NOT NULL DEFAULT FALSE,
                  migrated_at TIMESTAMP(3) NULL,
                  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  CONSTRAINT fk_career_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
        if (existing != null && existing == 0)
            jdbc.update("""
                    INSERT IGNORE INTO career_state (user_id, grade_base, high_water_mark, legacy)
                    SELECT id, 1000000, 1000000, TRUE FROM users WHERE password_hash NOT IN ('BOT', 'TRADER')
                    """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS personal_ledger (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  request_key VARCHAR(80) NOT NULL UNIQUE,
                  user_id BIGINT NOT NULL,
                  kind VARCHAR(16) NOT NULL,
                  amount BIGINT NOT NULL,
                  balance_after BIGINT NOT NULL,
                  memo VARCHAR(120) NOT NULL,
                  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  KEY ix_personal_ledger_user (user_id, id),
                  CONSTRAINT fk_personal_ledger_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS career_events (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  user_id BIGINT NOT NULL,
                  kind VARCHAR(16) NOT NULL,
                  from_grade VARCHAR(16) NULL,
                  to_grade VARCHAR(16) NULL,
                  asset_at BIGINT NOT NULL,
                  top_up BIGINT NOT NULL DEFAULT 0,
                  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  KEY ix_career_events_user (user_id, id),
                  CONSTRAINT fk_career_events_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
        // Admin cash added or taken since the grade started; returns leave it out.
        addColumnIfMissing(jdbc, "career_state", "outside_cash", "BIGINT NOT NULL DEFAULT 0 AFTER grade_base");
        // Settlements in a row that closed at or above the promotion return; any grade change resets it.
        addColumnIfMissing(jdbc, "career_state", "return_streak", "INT NOT NULL DEFAULT 0 AFTER outside_cash");
        addColumnIfMissing(jdbc, "attendance_rewards", "wallet_cash", "BIGINT NOT NULL DEFAULT 0");
        addColumnIfMissing(jdbc, "mission_rewards", "wallet_cash", "BIGINT NOT NULL DEFAULT 0");
        // Bets placed before the wallet existed were paid from trading cash and settle back there.
        addColumnIfMissing(jdbc, "tap_bets", "wallet", "BOOLEAN NOT NULL DEFAULT FALSE");
    }

    private static void addColumnIfMissing(JdbcTemplate jdbc, String table, String column, String definition) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, Integer.class, table, column);
        if (count != null && count == 0) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }
}
