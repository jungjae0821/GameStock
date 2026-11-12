package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Creates and migrates the title tables: owned titles plus the weekly return weeks and
 * snapshots. A package-private collaborator built by {@link TitleService}, which keeps
 * {@code ensureTables} as its entry point; it is not a Spring bean.
 */
final class TitleSchema {
    private final JdbcTemplate jdbc;

    TitleSchema(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void ensureTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS user_titles (
                  user_id BIGINT NOT NULL, title_id VARCHAR(40) NOT NULL,
                  win_count INT NOT NULL DEFAULT 1, notified BOOLEAN NOT NULL DEFAULT FALSE,
                  equipped BOOLEAN NOT NULL DEFAULT FALSE,
                  acquired_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY (user_id, title_id),
                  CONSTRAINT fk_user_title_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
        Integer equippedColumn = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'user_titles' AND column_name = 'equipped'", Integer.class);
        if (equippedColumn != null && equippedColumn == 0)
            jdbc.execute("ALTER TABLE user_titles ADD COLUMN equipped BOOLEAN NOT NULL DEFAULT FALSE AFTER notified");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS weekly_return_weeks (
                  week_start DATE NOT NULL PRIMARY KEY,
                  snapshot_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  closing_at TIMESTAMP NULL,
                  awarded_at TIMESTAMP NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS weekly_return_snapshots (
                  week_start DATE NOT NULL, user_id BIGINT NOT NULL, baseline_asset BIGINT NOT NULL,
                  closing_asset BIGINT NULL,
                  PRIMARY KEY (week_start, user_id),
                  CONSTRAINT fk_weekly_snapshot_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);
        addColumnIfMissing("weekly_return_weeks", "closing_at", "TIMESTAMP NULL AFTER snapshot_at");
        addColumnIfMissing("weekly_return_snapshots", "closing_asset", "BIGINT NULL AFTER baseline_asset");
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
        if (count != null && count == 0) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }
}
