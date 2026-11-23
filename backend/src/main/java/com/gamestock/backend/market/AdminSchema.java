package com.gamestock.backend.market;

import org.springframework.jdbc.core.JdbcTemplate;

/** Idempotent operations tables; called by the existing startup migration. */
final class AdminSchema {
    private AdminSchema() { }
    static void ensureStockState(JdbcTemplate db) {
        db.execute("""
            CREATE TABLE IF NOT EXISTS admin_stock_state (
              stock_id BIGINT PRIMARY KEY, state VARCHAR(16) NOT NULL,
              reason VARCHAR(300) NOT NULL, updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              FOREIGN KEY(stock_id) REFERENCES stocks(id))
            """);
    }
    static void ensure(JdbcTemplate db) {
        db.execute("""
            CREATE TABLE IF NOT EXISTS admin_permissions (
              user_id BIGINT NOT NULL, permission VARCHAR(24) NOT NULL,
              PRIMARY KEY(user_id, permission), FOREIGN KEY(user_id) REFERENCES users(id))
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS admin_audit (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, admin_id BIGINT NOT NULL,
              action VARCHAR(40) NOT NULL, target VARCHAR(128) NOT NULL, reason VARCHAR(300) NOT NULL,
              before_json MEDIUMTEXT NOT NULL, after_json MEDIUMTEXT NOT NULL,
              created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              INDEX ix_admin_audit_actor(admin_id,id), INDEX ix_admin_audit_action(action,id))
            """);
        ensureRuntime(db);
        ensureStockState(db);
        db.execute("""
            CREATE TABLE IF NOT EXISTS admin_stock_metadata (
              stock_id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL,
              developer VARCHAR(100) NOT NULL, genre VARCHAR(50) NOT NULL,
              FOREIGN KEY(stock_id) REFERENCES stocks(id))
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS admin_cash_adjustments (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, request_key VARCHAR(80) NOT NULL UNIQUE,
              admin_id BIGINT NOT NULL, user_id BIGINT NOT NULL, amount BIGINT NOT NULL,
              reason VARCHAR(300) NOT NULL, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              FOREIGN KEY(user_id) REFERENCES users(id))
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS admin_cash_epochs (
              user_id BIGINT PRIMARY KEY, last_adjustment_id BIGINT NOT NULL,
              FOREIGN KEY(user_id) REFERENCES users(id))
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS admin_content (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, kind VARCHAR(16) NOT NULL,
              title VARCHAR(150) NOT NULL, body TEXT NOT NULL, stock_id BIGINT NULL,
              published BOOLEAN NOT NULL DEFAULT FALSE, starts_at TIMESTAMP(3) NULL, ends_at TIMESTAMP(3) NULL,
              created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              FOREIGN KEY(stock_id) REFERENCES stocks(id), INDEX ix_admin_content_public(published,kind,id))
            """);
        // 공지 창의 머리 그림과 두 버튼. 비우면 그림 없이, 확인 버튼 하나로 보인다.
        addColumn(db,"admin_content","image_url","VARCHAR(500) NULL");
        addColumn(db,"admin_content","action_label","VARCHAR(40) NULL");
        addColumn(db,"admin_content","action_url","VARCHAR(500) NULL");
        addColumn(db,"admin_content","dismiss_label","VARCHAR(40) NULL");
        // 제목을 바꾸거나 숨겨도 재시작 시 중복 등록하거나 편집 내용을 덮어쓰지 않는다.
        addColumn(db,"admin_content","seed_key","VARCHAR(80) NULL UNIQUE");
        String syuenTitle = "슈엔증권 2.0";
        String syuenBody = "슈엔증권의 화면을 새롭게 다듬었어요. 시세와 차트, 주문 화면을 더 보기 쉽게 정리했어요.\n거래 탭에서 새 화면을 둘러보세요.";
        db.update("""
            INSERT INTO admin_content(kind,title,body,published,seed_key,image_url,action_label,action_url,dismiss_label)
            SELECT 'NOTICE',?,?,TRUE,'syuen-2.0','/notice/Syuen20.jpg','시장 둘러보기','/market','나중에 볼래요'
            WHERE NOT EXISTS (SELECT 1 FROM admin_content WHERE seed_key='syuen-2.0')
            """, syuenTitle, syuenBody);
        // 앞서 잘못 등록한 기본 문구만 복구하고, 관리자가 편집한 공지는 보존한다.
        db.update("""
            UPDATE admin_content SET title=?,body=?,image_url='/notice/Syuen20.jpg',
              action_label='시장 둘러보기',action_url='/market',dismiss_label='나중에 볼래요',
              updated_at=CURRENT_TIMESTAMP(3)
            WHERE seed_key='syuen-2.0' AND title='슈엔 2.0 공지' AND body=?
              AND image_url IS NULL AND action_label IS NULL AND action_url IS NULL AND dismiss_label IS NULL
            """, syuenTitle, syuenBody, "슈엔 2.0에 오신 것을 환영합니다.\n앞으로의 업데이트와 서비스 소식은 공지를 통해 안내드릴게요.");
        // 원본 PNG(2.1MB)는 공지 창이 열린 뒤에도 한참 비어 보였다. 같은 그림의 960px JPEG(50KB)로 바꾼다.
        // updated_at을 그대로 두어 이미 확인한 회원에게 다시 뜨지 않게 한다.
        db.update("""
            UPDATE admin_content SET image_url='/notice/Syuen20.jpg', updated_at=updated_at
            WHERE seed_key='syuen-2.0' AND image_url='/notice/Syuen20.png'
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS admin_mission_config (
              mission_id VARCHAR(40) PRIMARY KEY, enabled BOOLEAN NOT NULL,
              reward_cash BIGINT NOT NULL, minimum_watchlist INT NOT NULL DEFAULT 0)
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS user_reports (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, reporter_id BIGINT NOT NULL,
              target_user_id BIGINT NOT NULL, detail VARCHAR(1000) NOT NULL,
              status VARCHAR(16) NOT NULL DEFAULT 'OPEN', resolution VARCHAR(1000) NULL,
              resolved_by BIGINT NULL, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              FOREIGN KEY(reporter_id) REFERENCES users(id), FOREIGN KEY(target_user_id) REFERENCES users(id),
              INDEX ix_reports_status(status,id), INDEX ix_reports_reporter(reporter_id,id))
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS admin_seasons (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL,
              status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
              started_at TIMESTAMP(3) NULL, ended_at TIMESTAMP(3) NULL,
              created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3))
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS admin_season_entries (
              season_id BIGINT NOT NULL, user_id BIGINT NOT NULL, nickname VARCHAR(50) NOT NULL,
              opening_asset BIGINT NOT NULL, closing_asset BIGINT NULL, rank_no INT NULL,
              reward_cash BIGINT NOT NULL DEFAULT 0, PRIMARY KEY(season_id,user_id),
              FOREIGN KEY(season_id) REFERENCES admin_seasons(id), FOREIGN KEY(user_id) REFERENCES users(id))
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS promo_codes (
              code VARCHAR(40) PRIMARY KEY, reward_cash BIGINT NOT NULL DEFAULT 0,
              title_ids VARCHAR(500) NOT NULL DEFAULT '', max_uses INT NULL,
              starts_at TIMESTAMP(3) NULL, ends_at TIMESTAMP(3) NULL,
              active BOOLEAN NOT NULL DEFAULT TRUE, memo VARCHAR(200) NOT NULL DEFAULT '',
              created_by BIGINT NULL, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3))
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS promo_redemptions (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, code VARCHAR(40) NOT NULL, user_id BIGINT NOT NULL,
              reward_cash BIGINT NOT NULL, title_ids VARCHAR(500) NOT NULL,
              redeemed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              UNIQUE KEY uq_promo_user(code,user_id), FOREIGN KEY(user_id) REFERENCES users(id))
            """);
        // 코드에 고정돼 있던 첫 프로모션 코드. 관리자 화면에서 끄거나 고칠 수 있다.
        db.update("INSERT IGNORE INTO promo_codes(code,title_ids,memo) VALUES('syuen','special_syuangel,special_misilis_holder,special_syuaga','처음부터 있던 특별 칭호 코드')");
    }
    private static void addColumn(JdbcTemplate db,String table,String column,String definition){
        Integer count=db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",Integer.class,table,column);
        if(count!=null&&count==0)db.execute("ALTER TABLE "+table+" ADD COLUMN "+column+" "+definition);
    }
    private static void addRuntimeColumn(JdbcTemplate db,String column,String definition){addColumn(db,"admin_runtime",column,definition);}
    static void ensureRuntime(JdbcTemplate db) {
        db.execute("""
            CREATE TABLE IF NOT EXISTS admin_runtime (
              id INT PRIMARY KEY, bots_paused BOOLEAN NOT NULL DEFAULT FALSE,
              market_paused BOOLEAN NOT NULL DEFAULT FALSE,
              cadence_ms INT NULL, target_per_symbol INT NULL)
            """);
        db.update("INSERT IGNORE INTO admin_runtime(id) VALUES(1)");
        addRuntimeColumn(db,"traders_paused","BOOLEAN NOT NULL DEFAULT FALSE AFTER bots_paused");
        addRuntimeColumn(db,"trader_ramp_seconds","INT NULL");
        addRuntimeColumn(db,"traders_resumed_at","TIMESTAMP(3) NULL");
    }
    static void restoreStockMetadata(JdbcTemplate db){
        db.update("UPDATE games g JOIN stocks s ON s.game_id=g.id JOIN admin_stock_metadata a ON a.stock_id=s.id SET g.name=a.name,g.developer=a.developer,g.genre=a.genre");
    }
}
