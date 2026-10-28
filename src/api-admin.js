/**
 * Calista merchant app — admin API (v1.6).
 *
 * Every request from the Android app carries "Authorization: Bearer <token>".
 * handleAdmin() answers those and returns null for everything else, so the
 * website's own Basic-auth admin keeps working untouched.
 *
 * New in 1.6
 *   GET    /api/admin/app/bootstrap        everything the app needs, one round trip
 *   POST   /api/admin/app/orders           manual order (WhatsApp / Instagram / phone)
 *   POST   /api/admin/app/orders/bulk      one request for many orders
 *   PATCH  /api/admin/orders/:id           + note, address, COD received, "fake"
 *   GET/POST/DELETE /api/admin/app/blocked numbers refused at checkout
 *   GET/PATCH/DELETE /api/admin/app/reviews storefront review moderation
 * Columns the app needs are added automatically on first request.
 */

const STATUSES = ['pending', 'confirmed', 'packed', 'shipped', 'delivered', 'returned', 'cancelled', 'fake'];

const json = (data, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  });

const authed = (request, env) => {
  const h = request.headers.get('authorization') || '';
  if (!h.startsWith('Bearer ')) return false;
  const token = h.slice(7).trim();
  if (!token) return false;
  return token === env.ADMIN_TOKEN || token === env.ADMIN_PASSWORD;
};

const now = () => new Date().toISOString().replace('T', ' ').slice(0, 19);

// Same rule as the website checkout: 03XXXXXXXXX or null.
function normalisePhone(input) {
  let d = String(input || '').replace(/[^0-9]/g, '');
  if (d.startsWith('0092')) d = d.slice(4);
  else if (d.startsWith('92')) d = d.slice(2);
  if (d.startsWith('3')) d = '0' + d;
  return /^03[0-9]{9}$/.test(d) ? d : null;
}

let SCHEMA_OK = false;
async function ensureAppSchema(db) {
  if (SCHEMA_OK) return;
  try {
    const { results } = await db.prepare("SELECT name FROM pragma_table_info('orders')").all();
    const have = new Set((results || []).map((r) => r.name));
    const want = {
      updated_at: 'TEXT', courier: 'TEXT', tracking: 'TEXT', wa_status: "TEXT DEFAULT 'none'",
      wa_last_sent: 'TEXT', admin_note: 'TEXT', cod_paid_at: 'TEXT', source: 'TEXT',
      postex_tracking: 'TEXT', postex_status: 'TEXT', postex_booked_at: 'TEXT', verified_at: 'TEXT',
      risk_note: 'TEXT', discount_code: 'TEXT', discount_amount: 'INTEGER DEFAULT 0',
    };
    for (const [c, t] of Object.entries(want)) {
      if (!have.has(c)) {
        try { await db.prepare(`ALTER TABLE orders ADD COLUMN ${c} ${t}`).run(); } catch (e) {}
      }
    }
    for (const sql of [
      'CREATE TABLE IF NOT EXISTS blocked_phones (phone TEXT PRIMARY KEY, reason TEXT, created_at TEXT)',
      'CREATE TABLE IF NOT EXISTS wa_messages (id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER, direction TEXT, body TEXT, created_at TEXT)',
      'CREATE INDEX IF NOT EXISTS idx_orders_updated ON orders(updated_at)',
      'CREATE INDEX IF NOT EXISTS idx_orders_phone ON orders(phone)',
      'CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status)',
    ]) {
      try { await db.prepare(sql).run(); } catch (e) {}
    }
    SCHEMA_OK = true;
  } catch (e) { /* retried on the next request */ }
}

const ORDER_SELECT = `
  SELECT id, customer_name AS name, phone, city, address, status, 'COD' AS payment,
         total, created_at, items_json AS items,
         COALESCE(NULLIF(courier, ''),
                  CASE WHEN postex_tracking IS NOT NULL AND postex_tracking <> '' THEN 'PostEx' END,
                  '') AS courier,
         COALESCE(NULLIF(tracking, ''), postex_tracking, '') AS tracking,
         COALESCE(wa_status, 'none') AS wa_status,
         discount_code, discount_amount, risk_note, admin_note, cod_paid_at, source,
         postex_status, updated_at
  FROM orders`;

const PRODUCT_SELECT = `
  SELECT id, name, slug, fabric, price, sale_price, stock, is_active AS active,
         COALESCE(NULLIF(image_url, ''), NULLIF(image_url_2, ''),
                  json_extract(CASE WHEN json_valid(images_json) THEN images_json END, '$[0]'), '') AS image_url
  FROM products`;

// Sales exclude orders that never turned into money.
function statsQueries(db, days) {
  const from = `-${days} days`;
  return [
    db.prepare(
      `SELECT COALESCE(SUM(CASE WHEN status NOT IN ('cancelled','fake','returned') THEN total END), 0) AS sales,
              COUNT(*) AS orders,
              COALESCE(SUM(CASE WHEN status = 'shipped' THEN total END), 0) AS cod,
              SUM(CASE WHEN status = 'shipped' THEN 1 ELSE 0 END) AS cod_count,
              SUM(CASE WHEN status = 'returned' THEN 1 ELSE 0 END) AS returned
       FROM orders WHERE created_at >= datetime('now', ?)`
    ).bind(from),
    // Pakistan time, so a 1 am order lands on the right day.
    db.prepare(
      `SELECT date(created_at, '+5 hours') AS d,
              COALESCE(SUM(CASE WHEN status NOT IN ('cancelled','fake','returned') THEN total END), 0) AS v
       FROM orders WHERE created_at >= datetime('now', ?)
       GROUP BY d ORDER BY d`
    ).bind(from),
  ];
}

function statsJson(agg, daily, days) {
  const byDay = {};
  (daily || []).forEach((r) => { byDay[r.d] = r.v; });
  const series = [];
  const pk = Date.now() + 5 * 3600 * 1000;
  const n = Math.min(days, 30);
  for (let i = n - 1; i >= 0; i--) {
    const d = new Date(pk - i * 86400000).toISOString().slice(0, 10);
    series.push(byDay[d] || 0);
  }
  const a = agg || {};
  return {
    sales: a.sales || 0,
    orders: a.orders || 0,
    cod: a.cod || 0,
    cod_count: a.cod_count || 0,
    return_rate: a.orders ? `${Math.round(((a.returned || 0) * 100) / a.orders)}%` : '-',
    series,
  };
}

const rangeDays = (r) => {
  const n = parseInt(String(r || '7').replace(/[^0-9]/g, ''), 10);
  return n > 0 ? Math.min(n, 365) : 1;
};

async function postexCreds(db, env) {
  let token = env.POSTEX_TOKEN || null;
  let pickup = env.POSTEX_PICKUP_ADDRESS_CODE || null;
  if (!token || !pickup) {
    try {
      const { results } = await db
        .prepare("SELECT key, value FROM site_settings WHERE key IN ('postex_token', 'postex_pickup_code')")
        .all();
      for (const r of results || []) {
        if (r.key === 'postex_token' && !token) token = (r.value || '').trim();
        if (r.key === 'postex_pickup_code' && !pickup) pickup = (r.value || '').trim();
      }
    } catch (e) {}
  }
  return { token, pickup: pickup || '001' };
}

// Shared by single and bulk order edits.
function orderSets(b) {
  const sets = [];
  const vals = [];
  if (b.status !== undefined) {
    if (!STATUSES.includes(b.status)) return { error: 'bad status' };
    sets.push('status = ?'); vals.push(b.status);
    if (b.status === 'confirmed') sets.push("verified_at = COALESCE(verified_at, datetime('now'))");
  }
  if (b.admin_note !== undefined) { sets.push('admin_note = ?'); vals.push(b.admin_note || null); }
  if (b.name !== undefined) { sets.push('customer_name = ?'); vals.push(String(b.name).trim()); }
  if (b.address !== undefined) { sets.push('address = ?'); vals.push(String(b.address).trim()); }
  if (b.city !== undefined) { sets.push('city = ?'); vals.push(String(b.city).trim()); }
  if (b.phone !== undefined) {
    const p = normalisePhone(b.phone);
    if (!p) return { error: 'Not a valid mobile number' };
    sets.push('phone = ?'); vals.push(p);
  }
  if (b.cod_paid !== undefined) {
    sets.push('cod_paid_at = ?'); vals.push(b.cod_paid ? now() : null);
  }
  if (!sets.length) return { error: 'nothing to update' };
  sets.push('updated_at = ?'); vals.push(now());
  return { sets, vals };
}

export async function handleAdmin(request, env) {
  const url = new URL(request.url);
  const p = url.pathname;
  if (!p.startsWith('/api/admin/')) return null;
  if (!authed(request, env)) return null;

  const db = env.DB;
  if (!db) return json({ error: 'D1 binding "DB" missing in wrangler.toml' }, 500);
  await ensureAppSchema(db);

  const m = request.method;
  let mm;

  // ── one-shot load ──────────────────────────────────────────────────────
  if (p === '/api/admin/app/bootstrap' && m === 'GET') {
    const limit = Math.min(parseInt(url.searchParams.get('limit') || '300', 10), 500);
    const days = rangeDays(url.searchParams.get('range'));
    const [orders, products, agg, daily, discounts, banners, fabrics, blocked] = await db.batch([
      db.prepare(`${ORDER_SELECT} ORDER BY id DESC LIMIT ?`).bind(limit),
      db.prepare(`${PRODUCT_SELECT} ORDER BY id DESC LIMIT 500`),
      ...statsQueries(db, days),
      db.prepare('SELECT * FROM discounts ORDER BY is_active DESC, id DESC'),
      db.prepare('SELECT * FROM banners ORDER BY sort_order ASC, id ASC'),
      db.prepare('SELECT name FROM fabric_categories ORDER BY sort_order ASC'),
      db.prepare('SELECT phone, reason, created_at FROM blocked_phones ORDER BY created_at DESC'),
    ]);
    return json({
      server_time: now(),
      orders: orders.results || [],
      products: products.results || [],
      stats: statsJson((agg.results || [])[0], daily.results, days),
      discounts: discounts.results || [],
      banners: banners.results || [],
      fabrics: (fabrics.results || []).map((r) => r.name).filter(Boolean),
      blocked: blocked.results || [],
    });
  }

  // ── orders ─────────────────────────────────────────────────────────────
  if (p === '/api/admin/orders' && m === 'GET') {
    const limit = Math.min(parseInt(url.searchParams.get('limit') || '120', 10), 500);
    const since = url.searchParams.get('since');
    const stmt = since
      ? db.prepare(`${ORDER_SELECT} WHERE updated_at >= ? ORDER BY updated_at DESC LIMIT ?`).bind(since, limit)
      : db.prepare(`${ORDER_SELECT} ORDER BY id DESC LIMIT ?`).bind(limit);
    const { results } = await stmt.all();
    return json({ orders: results || [], server_time: now() });
  }

  if (p === '/api/admin/app/orders' && m === 'POST') {
    const b = await request.json();
    const phone = normalisePhone(b.phone);
    if (!b.name || !phone || !b.address || !b.city) {
      return json({ error: 'Name, a valid mobile number, address and city are needed' }, 400);
    }
    const lines = Array.isArray(b.items) ? b.items : [];
    if (!lines.length) return json({ error: 'Add at least one item' }, 400);
    const ids = lines.map((i) => parseInt(i.product_id, 10)).filter(Boolean);
    const byId = {};
    if (ids.length) {
      const { results } = await db
        .prepare(`SELECT id, name, price, sale_price FROM products WHERE id IN (${ids.map(() => '?').join(',')})`)
        .bind(...ids)
        .all();
      (results || []).forEach((r) => { byId[r.id] = r; });
    }
    const priced = lines.map((i) => {
      const pr = byId[parseInt(i.product_id, 10)];
      const list = pr ? (Number(pr.sale_price) > 0 ? Number(pr.sale_price) : Number(pr.price)) : 0;
      return {
        product_id: pr ? pr.id : null,
        name: (pr && pr.name) || String(i.name || 'Item'),
        size: i.size || null,
        qty: Math.max(1, Math.min(50, parseInt(i.qty, 10) || 1)),
        price: i.price != null && i.price !== '' ? Number(i.price) : list,
      };
    });
    const subtotal = priced.reduce((s, i) => s + i.price * i.qty, 0);
    const discount = Math.max(0, parseInt(b.discount_amount, 10) || 0);
    const total = Math.max(0, subtotal - discount);
    const status = STATUSES.includes(b.status) ? b.status : 'confirmed';
    const t = now();
    const r = await db
      .prepare(
        `INSERT INTO orders (customer_name, phone, address, city, items_json, total, discount_amount,
                             status, admin_note, source, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
      )
      .bind(String(b.name).trim(), phone, String(b.address).trim(), String(b.city).trim(),
            JSON.stringify(priced), total, discount, status, b.note || null, b.source || 'app', t, t)
      .run();
    const stock = priced.filter((i) => i.product_id).map((i) =>
      db.prepare('UPDATE products SET stock = MAX(0, stock - ?) WHERE id = ? AND stock IS NOT NULL').bind(i.qty, i.product_id)
    );
    if (stock.length) { try { await db.batch(stock); } catch (e) {} }
    return json({ ok: true, id: r.meta && r.meta.last_row_id, total });
  }

  if (p === '/api/admin/app/orders/bulk' && m === 'POST') {
    const b = await request.json();
    const ids = (Array.isArray(b.ids) ? b.ids : []).map((x) => parseInt(x, 10)).filter(Boolean).slice(0, 300);
    if (!ids.length) return json({ error: 'no orders' }, 400);
    const s = orderSets(b);
    if (s.error) return json({ error: s.error }, 400);
    await db
      .prepare(`UPDATE orders SET ${s.sets.join(', ')} WHERE id IN (${ids.map(() => '?').join(',')})`)
      .bind(...s.vals, ...ids)
      .run();
    return json({ ok: true, updated: ids.length });
  }

  mm = p.match(/^\/api\/admin\/orders\/(\d+)$/);
  if (mm && m === 'PATCH') {
    const s = orderSets(await request.json());
    if (s.error) return json({ error: s.error }, 400);
    await db.prepare(`UPDATE orders SET ${s.sets.join(', ')} WHERE id = ?`).bind(...s.vals, mm[1]).run();
    return json({ ok: true });
  }

  mm = p.match(/^\/api\/admin\/orders\/(\d+)\/whatsapp$/);
  if (mm && m === 'POST') {
    const body = await request.json();
    const t = now();
    await db.batch([
      db.prepare('INSERT INTO wa_messages (order_id, direction, body, created_at) VALUES (?, ?, ?, ?)')
        .bind(mm[1], 'out', body.template || '', t),
      db.prepare("UPDATE orders SET wa_status = 'sent', wa_last_sent = ?, updated_at = ? WHERE id = ? AND COALESCE(wa_status, 'none') IN ('none', 'sent')")
        .bind(t, t, mm[1]),
    ]);
    return json({ ok: true });
  }

  if (p === '/api/admin/stats' && m === 'GET') {
    const days = rangeDays(url.searchParams.get('range') || '1');
    const [agg, daily] = await db.batch(statsQueries(db, days));
    return json(statsJson((agg.results || [])[0], daily.results, days));
  }

  // ── blocklist ──────────────────────────────────────────────────────────
  if (p === '/api/admin/app/blocked' && m === 'GET') {
    const { results } = await db.prepare('SELECT phone, reason, created_at FROM blocked_phones ORDER BY created_at DESC').all();
    return json({ blocked: results || [] });
  }
  if (p === '/api/admin/app/blocked' && m === 'POST') {
    const b = await request.json();
    const phone = normalisePhone(b.phone);
    if (!phone) return json({ error: 'Not a valid mobile number' }, 400);
    await db.prepare('INSERT OR REPLACE INTO blocked_phones (phone, reason, created_at) VALUES (?, ?, ?)')
      .bind(phone, b.reason || null, now()).run();
    return json({ ok: true, phone });
  }
  mm = p.match(/^\/api\/admin\/app\/blocked\/([0-9]+)$/);
  if (mm && m === 'DELETE') {
    await db.prepare('DELETE FROM blocked_phones WHERE phone = ?').bind(normalisePhone(mm[1]) || mm[1]).run();
    return json({ ok: true });
  }

  // ── reviews ────────────────────────────────────────────────────────────
  if (p === '/api/admin/app/reviews' && m === 'GET') {
    const { results } = await db.prepare(
      `SELECT r.id, r.product_id, r.customer_name, r.city, r.rating, r.body, r.is_approved, r.created_at,
              p.name AS product_name
       FROM reviews r LEFT JOIN products p ON p.id = r.product_id
       ORDER BY r.is_approved ASC, r.id DESC LIMIT 200`
    ).all();
    return json({ reviews: results || [] });
  }
  mm = p.match(/^\/api\/admin\/app\/reviews\/(\d+)$/);
  if (mm && m === 'PATCH') {
    const b = await request.json();
    await db.prepare('UPDATE reviews SET is_approved = ? WHERE id = ?').bind(b.is_approved ? 1 : 0, mm[1]).run();
    return json({ ok: true });
  }
  if (mm && m === 'DELETE') {
    await db.prepare('DELETE FROM reviews WHERE id = ?').bind(mm[1]).run();
    return json({ ok: true });
  }

  // ── products ───────────────────────────────────────────────────────────
  if (p === '/api/admin/products' && m === 'GET') {
    const limit = Math.min(parseInt(url.searchParams.get('limit') || '200', 10), 500);
    const { results } = await db.prepare(`${PRODUCT_SELECT} ORDER BY id DESC LIMIT ?`).bind(limit).all();
    return json({ products: results || [] });
  }

  mm = p.match(/^\/api\/admin\/products\/(\d+)$/);
  if (mm && m === 'PATCH') {
    const body = await request.json();
    const cols = { stock: 'stock', price: 'price', sale_price: 'sale_price', active: 'is_active', name: 'name', fabric: 'fabric', image: 'image_url', description: 'description' };
    const sets = [];
    const vals = [];
    for (const k of Object.keys(cols)) {
      if (body[k] !== undefined) { sets.push(`${cols[k]} = ?`); vals.push(body[k]); }
    }
    if (body.image !== undefined) {
      sets.push('images_json = ?');
      vals.push(body.image ? JSON.stringify([body.image]) : null);
    }
    if (!sets.length) return json({ error: 'nothing to update' }, 400);
    await db.prepare(`UPDATE products SET ${sets.join(', ')} WHERE id = ?`).bind(...vals, mm[1]).run();
    return json({ ok: true });
  }

  if (mm && m === 'DELETE') {
    await db.prepare('DELETE FROM products WHERE id = ?').bind(mm[1]).run();
    return json({ ok: true });
  }

  if (p === '/api/admin/products' && m === 'POST') {
    const b = await request.json();
    if (!b.name) return json({ error: 'name required' }, 400);
    const slug = String(b.name).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
      + '-' + Math.random().toString(36).slice(2, 6);
    const r = await db
      .prepare(
        `INSERT INTO products (name, slug, fabric, description, price, sale_price, stock, image_url,
             images_json, is_active, sort_order, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
             (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM products), datetime('now'))`
      )
      .bind(
        b.name, slug, b.fabric || null, b.description || null, b.price || 0,
        b.sale_price || null, b.stock || 0, b.image || '',
        b.image ? JSON.stringify([b.image]) : null,
        b.active === 0 ? 0 : 1
      )
      .run();
    return json({ ok: true, id: r.meta && r.meta.last_row_id, slug });
  }

  if (p === '/api/admin/app/upload' && m === 'POST') {
    if (!env.IMAGES) return json({ error: 'R2 binding "IMAGES" missing' }, 500);
    const form = await request.formData();
    const urls = [];
    for (const f of form.getAll('files')) {
      if (!f || typeof f === 'string') continue;
      const raw = (f.name || '').split('.').pop().toLowerCase();
      const ext = /^[a-z0-9]{2,5}$/.test(raw) ? raw : 'jpg';
      const key = `uploads/${Date.now()}-${Math.random().toString(36).slice(2, 8)}.${ext}`;
      await env.IMAGES.put(key, await f.arrayBuffer(), {
        httpMetadata: { contentType: f.type || 'image/jpeg', cacheControl: 'public, max-age=31536000, immutable' },
      });
      urls.push('/' + key);
    }
    if (!urls.length) return json({ error: 'no files received' }, 400);
    return json({ ok: true, urls });
  }

  if (p === '/api/admin/app/fabrics' && m === 'GET') {
    const { results } = await db.prepare('SELECT name FROM fabric_categories ORDER BY sort_order ASC').all();
    return json({ fabrics: (results || []).map((r) => r.name) });
  }

  // ── discounts ──────────────────────────────────────────────────────────
  if (p === '/api/admin/app/discounts' && m === 'GET') {
    const { results } = await db.prepare('SELECT * FROM discounts ORDER BY is_active DESC, id DESC').all();
    return json({ discounts: results || [] });
  }

  if (p === '/api/admin/app/discounts' && m === 'POST') {
    const d = await request.json();
    if (!d.title || !d.type) return json({ error: 'title and type required' }, 400);
    const r = await db
      .prepare(
        `INSERT INTO discounts (code, title, type, value, buy_quantity, get_quantity,
             get_discount_percent, min_cart_value, usage_limit, start_date, end_date, is_active)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
      )
      .bind(
        d.code ? String(d.code).trim().toUpperCase() : null,
        d.title, d.type, d.value || null,
        d.buy_quantity || null, d.get_quantity || null,
        d.get_discount_percent == null ? 100 : d.get_discount_percent,
        d.min_cart_value || null, d.usage_limit || null,
        d.start_date || null, d.end_date || null,
        d.is_active === 0 ? 0 : 1
      )
      .run();
    return json({ ok: true, id: r.meta && r.meta.last_row_id });
  }

  mm = p.match(/^\/api\/admin\/app\/discounts\/(\d+)$/);
  if (mm && m === 'PATCH') {
    const b = await request.json();
    const sets = [];
    const vals = [];
    for (const k of ['code', 'title', 'type', 'value', 'min_cart_value', 'usage_limit', 'start_date',
                     'end_date', 'is_active', 'buy_quantity', 'get_quantity', 'get_discount_percent']) {
      if (b[k] !== undefined) { sets.push(k + ' = ?'); vals.push(b[k]); }
    }
    if (!sets.length) return json({ error: 'nothing to update' }, 400);
    await db.prepare(`UPDATE discounts SET ${sets.join(', ')} WHERE id = ?`).bind(...vals, mm[1]).run();
    return json({ ok: true });
  }
  if (mm && m === 'DELETE') {
    await db.prepare('DELETE FROM discounts WHERE id = ?').bind(mm[1]).run();
    return json({ ok: true });
  }

  // ── banners ────────────────────────────────────────────────────────────
  if (p === '/api/admin/app/banners' && m === 'GET') {
    const { results } = await db.prepare('SELECT * FROM banners ORDER BY sort_order ASC, id ASC').all();
    return json({ banners: results || [] });
  }

  if (p === '/api/admin/app/banners' && m === 'POST') {
    const b = await request.json();
    if (!b.heading) return json({ error: 'heading required' }, 400);
    const r = await db
      .prepare(
        `INSERT INTO banners (eyebrow, heading, subheading, button_text, button_link, image_url, sort_order)
         VALUES (?, ?, ?, ?, ?, ?, (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM banners))`
      )
      .bind(b.eyebrow || null, b.heading, b.subheading || null, b.button_text || null,
            b.button_link || null, b.image_url || null)
      .run();
    return json({ ok: true, id: r.meta && r.meta.last_row_id });
  }

  mm = p.match(/^\/api\/admin\/app\/banners\/(\d+)$/);
  if (mm && m === 'PATCH') {
    const b = await request.json();
    const sets = [];
    const vals = [];
    for (const k of ['eyebrow', 'heading', 'subheading', 'button_text', 'button_link', 'image_url', 'sort_order', 'is_active']) {
      if (b[k] !== undefined) { sets.push(k + ' = ?'); vals.push(b[k]); }
    }
    if (!sets.length) return json({ error: 'nothing to update' }, 400);
    await db.prepare(`UPDATE banners SET ${sets.join(', ')} WHERE id = ?`).bind(...vals, mm[1]).run();
    return json({ ok: true });
  }
  if (mm && m === 'DELETE') {
    await db.prepare('DELETE FROM banners WHERE id = ?').bind(mm[1]).run();
    return json({ ok: true });
  }

  // ── PostEx ─────────────────────────────────────────────────────────────
  if (p === '/api/admin/courier/postex' && m === 'POST') {
    const { orderId } = await request.json();
    const o = await db.prepare(`${ORDER_SELECT} WHERE id = ?`).bind(orderId).first();
    if (!o) return json({ ok: false, reason: 'order not found' }, 404);
    const { token, pickup } = await postexCreds(db, env);
    if (!token) return json({ ok: false, reason: 'PostEx token not set (Worker secret or admin settings)' }, 200);

    let items = [];
    try { items = JSON.parse(o.items || '[]'); } catch (e) {}
    const detail = items.map((i) => `${i.name}${i.size ? ' (' + i.size + ')' : ''} x${i.qty}`).join(', ');

    const res = await fetch('https://api.postex.pk/services/integration/api/order/v3/create-order', {
      method: 'POST',
      headers: { token, 'content-type': 'application/json' },
      body: JSON.stringify({
        cityName: o.city,
        customerName: o.name,
        customerPhone: o.phone,
        deliveryAddress: o.address,
        invoicePayment: o.total,
        orderDetail: detail || `Order #${o.id}`,
        orderRefNumber: String(o.id),
        orderType: 'Normal',
        transactionNotes: o.admin_note || '',
        items: Math.max(1, items.reduce((s, i) => s + (parseInt(i.qty, 10) || 1), 0)),
        pickupAddressCode: pickup,
      }),
    });
    const data = await res.json().catch(() => ({}));
    const cn = (data && data.dist && data.dist.trackingNumber) || (data && data.trackingNumber);
    if (!res.ok || !cn) {
      return json({ ok: false, reason: (data && data.statusMessage) || `PostEx ${res.status}` }, 200);
    }
    const t = now();
    await db
      .prepare(
        `UPDATE orders SET courier = 'PostEx', tracking = ?, postex_tracking = ?, postex_booked_at = ?,
                           status = 'shipped', updated_at = ? WHERE id = ?`
      )
      .bind(cn, cn, t, t, o.id)
      .run();
    return json({ ok: true, tracking: cn });
  }

  mm = p.match(/^\/api\/admin\/courier\/postex\/track\/(.+)$/);
  if (mm && m === 'GET') {
    const { token } = await postexCreds(db, env);
    const res = await fetch(
      `https://api.postex.pk/services/integration/api/order/v1/track-order/${encodeURIComponent(mm[1])}`,
      { headers: { token: token || '' } }
    );
    const data = await res.json().catch(() => ({}));
    const d = (data && data.dist) || data || {};
    const status = d.transactionStatus || d.orderStatus || d.status || '';
    if (status) {
      await db.prepare('UPDATE orders SET postex_status = ?, updated_at = ? WHERE tracking = ? OR postex_tracking = ?')
        .bind(status, now(), mm[1], mm[1]).run();
    }
    return json({ ok: res.ok, status, raw: data }, 200);
  }

  return null;
}
