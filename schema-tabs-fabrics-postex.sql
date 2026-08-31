-- Run once (or click "Run database migration" in the admin panel's Site tab).
ALTER TABLE nav_tabs ADD COLUMN fabrics TEXT;
ALTER TABLE nav_tabs ADD COLUMN show_in_topbar INTEGER NOT NULL DEFAULT 1;
ALTER TABLE orders ADD COLUMN postex_tracking TEXT;
ALTER TABLE orders ADD COLUMN postex_status TEXT;
ALTER TABLE orders ADD COLUMN postex_booked_at TEXT;
