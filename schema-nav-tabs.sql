CREATE TABLE IF NOT EXISTS nav_tabs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  label TEXT NOT NULL,
  slug TEXT NOT NULL UNIQUE,
  gender TEXT NOT NULL DEFAULT 'women',
  sort_order INTEGER NOT NULL DEFAULT 0,
  is_active INTEGER NOT NULL DEFAULT 1,
  created_at TEXT DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS nav_tab_products (
  tab_id INTEGER NOT NULL,
  product_id INTEGER NOT NULL,
  PRIMARY KEY (tab_id, product_id)
);

INSERT INTO nav_tabs (label, slug, gender, sort_order) VALUES
('Unstitched Lawn', 'unstitched-lawn', 'women', 0),
('Chiffon Collection', 'chiffon-collection', 'women', 1),
('New Arrivals', 'new-arrivals-women', 'women', 2),
('Sale', 'sale-women', 'women', 3),
('Men Kurta', 'men-kurta', 'men', 0),
('Men Shalwar Kameez', 'men-shalwar-kameez', 'men', 1);
