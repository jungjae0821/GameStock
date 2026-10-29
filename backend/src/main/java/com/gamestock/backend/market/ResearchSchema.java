package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

/** Additive schema for stock communities and user-owned strategy projects. */
final class ResearchSchema {
    static void ensureTables(JdbcTemplate db) {
        db.execute("""
            CREATE TABLE IF NOT EXISTS stock_discussions (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, stock_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
              parent_id BIGINT NULL, kind VARCHAR(8) NOT NULL, title VARCHAR(120) NOT NULL DEFAULT '',
              body TEXT NOT NULL, deleted BOOLEAN NOT NULL DEFAULT FALSE,
              created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              KEY ix_discussion_feed(stock_id,kind,parent_id,id), KEY ix_discussion_author(user_id,id),
              FOREIGN KEY(stock_id) REFERENCES stocks(id), FOREIGN KEY(user_id) REFERENCES users(id),
              FOREIGN KEY(parent_id) REFERENCES stock_discussions(id)
            )
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS research_write_limits (
              user_id BIGINT NOT NULL, channel VARCHAR(12) NOT NULL, window_no BIGINT NOT NULL,
              actions INT NOT NULL, PRIMARY KEY(user_id,channel), FOREIGN KEY(user_id) REFERENCES users(id)
            )
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS quant_projects (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, name VARCHAR(80) NOT NULL,
              stock_code VARCHAR(32) NOT NULL, chart_range VARCHAR(4) NOT NULL,
              language VARCHAR(12) NOT NULL, source MEDIUMTEXT NOT NULL, program MEDIUMTEXT NOT NULL,
              revision INT NOT NULL DEFAULT 1, status VARCHAR(12) NOT NULL DEFAULT 'PAUSED',
              max_orders INT NOT NULL, max_notional BIGINT NOT NULL,
              orders_used INT NOT NULL DEFAULT 0, notional_used BIGINT NOT NULL DEFAULT 0,
              last_bar VARCHAR(40) NULL, last_checked TIMESTAMP(3) NULL,
              created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              KEY ix_quant_owner(user_id,id), KEY ix_quant_poll(status,last_checked,id),
              FOREIGN KEY(user_id) REFERENCES users(id)
            )
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS quant_runs (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, project_id BIGINT NOT NULL, revision INT NOT NULL,
              bar_time VARCHAR(40) NOT NULL, side VARCHAR(4) NOT NULL, quantity INT NOT NULL,
              limit_price BIGINT NOT NULL, status VARCHAR(12) NOT NULL DEFAULT 'SUBMITTING',
              message VARCHAR(240) NOT NULL DEFAULT '', order_id BIGINT NULL,
              created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              KEY ix_quant_runs(project_id,id), FOREIGN KEY(project_id) REFERENCES quant_projects(id)
            )
            """);
    }
}
