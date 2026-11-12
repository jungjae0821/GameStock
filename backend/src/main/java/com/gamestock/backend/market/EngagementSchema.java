package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tables for fandom, public profiles, daily predictions and feature unlocks.
 *
 * <p>{@code user_engagement} holds one optional row per user. The first time the table is
 * created every existing account gets {@code unlock_all = TRUE}, so people who already
 * traded futures, options, quant or tap before the unlock system keep their access.
 * Accounts created later have no row (or a row with FALSE) and unlock by activity.
 */
final class EngagementSchema {
    private EngagementSchema() { }

    static void ensureTables(JdbcTemplate jdbc) {
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='user_engagement'",
                Integer.class);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS user_engagement (
                  user_id BIGINT NOT NULL PRIMARY KEY,
                  fandom_stock_id BIGINT NULL,
                  public_profile BOOLEAN NOT NULL DEFAULT FALSE,
                  unlock_all BOOLEAN NOT NULL DEFAULT FALSE,
                  fandom_changed_at TIMESTAMP NULL,
                  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  INDEX ix_engagement_fandom (fandom_stock_id),
                  CONSTRAINT fk_engagement_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
        if (existing != null && existing == 0)
            jdbc.update("INSERT IGNORE INTO user_engagement (user_id, unlock_all) SELECT id, TRUE FROM users");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS daily_predictions (
                  user_id BIGINT NOT NULL,
                  day DATE NOT NULL,
                  stock_id BIGINT NOT NULL,
                  base_price BIGINT NOT NULL,
                  close_price BIGINT NULL,
                  result VARCHAR(8) NULL,
                  reward_cash BIGINT NOT NULL DEFAULT 0,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY (user_id, day),
                  INDEX ix_prediction_day_result (day, result),
                  CONSTRAINT fk_prediction_user FOREIGN KEY (user_id) REFERENCES users(id),
                  CONSTRAINT fk_prediction_stock FOREIGN KEY (stock_id) REFERENCES stocks(id)
                )
                """);
    }
}
