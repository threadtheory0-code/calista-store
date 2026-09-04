-- Customer reviews. Applied automatically on the first request after deploy;
-- this file is here if you'd rather run it by hand in the D1 console.

CREATE TABLE IF NOT EXISTS reviews (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  product_id INTEGER,               -- NULL = a store-wide review
  customer_name TEXT NOT NULL,      -- first name / first name + initial
  city TEXT,
  rating INTEGER NOT NULL DEFAULT 5,
  body TEXT,
  consent INTEGER NOT NULL DEFAULT 0,     -- customer said yes to publishing
  is_approved INTEGER NOT NULL DEFAULT 0, -- only settable when consent = 1
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TEXT DEFAULT (datetime('now'))
);

-- Cash-on-delivery bookkeeping on orders.
ALTER TABLE orders ADD COLUMN verified_at TEXT;
ALTER TABLE orders ADD COLUMN risk_note TEXT;
