function calculateDiscount(discount, cart) {
  if (!Array.isArray(cart) || cart.length === 0) return { error: 'Cart is empty' };
  const cartTotal = cart.reduce((sum, i) => sum + i.price * i.qty, 0);
  const totalQty = cart.reduce((sum, i) => sum + i.qty, 0);

  if (discount.min_cart_value && cartTotal < discount.min_cart_value) {
    return { error: `Add Rs. ${Math.ceil(discount.min_cart_value - cartTotal)} more to use this code` };
  }

  if (discount.type === 'percentage_off_order') {
    return { amount: Math.round(cartTotal * (discount.value / 100)) };
  }

  if (discount.type === 'fixed_off_order') {
    return { amount: Math.min(discount.value, cartTotal) };
  }

  if (discount.type === 'buy_x_get_y') {
    const buyQty = discount.buy_quantity || 1;
    const getQty = discount.get_quantity || 1;
    const groupSize = buyQty + getQty;
    if (totalQty < groupSize) return { error: `Add ${groupSize - totalQty} more item(s) to unlock this offer` };

    // Expand cart into individual unit prices, cheapest-first, so the discount applies to the lowest-priced eligible units
    const units = [];
    cart.forEach(item => { for (let i = 0; i < item.qty; i++) units.push(item.price); });
    units.sort((a, b) => a - b);

    const eligibleGroups = Math.floor(units.length / groupSize);
    let discountAmount = 0;
    for (let g = 0; g < eligibleGroups; g++) {
      const groupUnits = units.slice(g * groupSize, g * groupSize + groupSize);
      const freeUnits = groupUnits.slice(0, getQty); // cheapest units in each group get discounted
      freeUnits.forEach(price => { discountAmount += price * ((discount.get_discount_percent || 100) / 100); });
    }
    return { amount: Math.round(discountAmount) };
  }

  return { error: 'Unsupported discount type' };
}

function checkAdminAuth(request, env) {
  const auth = request.headers.get('Authorization');
  if (!auth || !auth.startsWith('Basic ')) return false;
  let decoded;
  try {
    decoded = atob(auth.slice(6));
  } catch (e) {
    return false;
  }
  const sep = decoded.indexOf(':');
  const user = decoded.slice(0, sep);
  const pass = decoded.slice(sep + 1);
  return user === 'admin' && pass === env.ADMIN_PASSWORD && !!env.ADMIN_PASSWORD;
}

function unauthorized() {
  return new Response('Authentication required', {
    status: 401,
    headers: { 'WWW-Authenticate': 'Basic realm="Calista Admin"' }
  });
}

// "Lawn, dhanak ,Karandi" -> "lawn,dhanak,karandi"
/* The columns a product CARD needs — and nothing else.
   `SELECT *` was shipping every product's full description (2 KB of
   pasted HTML each) and its whole gallery list to every grid, so a
   category page downloaded well over a megabyte of JSON to render
   photos, names and prices. The product page still reads the full row
   for the one product it shows. */
const CARD_COLS = [
  'p.id', 'p.name', 'p.slug', 'p.fabric', 'p.price', 'p.sale_price',
  'p.image_url', 'p.image_url_2', 'p.stock', 'p.sizes', 'p.sort_order'
].join(', ');

// tabIds: hand-picked assignments to include. fabrics: fabric names to match.
// allIfEmpty: when neither is configured, return the whole catalogue.
async function productsInScope(env, tabIds, fabrics, allIfEmpty) {
  const order = 'ORDER BY p.sort_order ASC, p.id DESC';
  if (!tabIds.length && !fabrics.length) {
    if (!allIfEmpty) return [];
    const { results } = await env.DB
      .prepare(`SELECT ${CARD_COLS} FROM products p WHERE p.is_active = 1 ${order}`).all();
    return results;
  }
  const conds = [], binds = [];
  if (tabIds.length) {
    conds.push(`p.id IN (SELECT product_id FROM nav_tab_products WHERE tab_id IN (${tabIds.map(() => '?').join(',')}))`);
    binds.push(...tabIds);
  }
  if (fabrics.length) {
    conds.push(`LOWER(TRIM(p.fabric)) IN (${fabrics.map(() => '?').join(',')})`);
    binds.push(...fabrics);
  }
  const { results } = await env.DB.prepare(
    `SELECT ${CARD_COLS} FROM products p WHERE p.is_active = 1 AND (${conds.join(' OR ')}) ${order}`
  ).bind(...binds).all();
  return results;
}

/* ============================================================
   SCHEMA MIGRATIONS
   This used to run on every request that touched the database,
   including every storefront product load. It fired nine
   "ALTER TABLE … ADD COLUMN" statements of which eight always
   failed (the columns were added weeks ago) — but each failure
   was still a full round trip to D1, in sequence, before the
   product query could even start. Cloudflare evicts idle
   isolates constantly, so a large share of visitors paid for all
   nine.

   Now: one cheap version read decides whether anything needs
   doing, and storefront reads never wait for it at all — they
   hand it to ctx.waitUntil and answer immediately.
   ============================================================ */
const SCHEMA_VERSION = 4;
let SCHEMA_READY = false;
let SCHEMA_RUNNING = null;

async function runMigrations(env) {
  try {
    const row = await env.DB
      .prepare("SELECT value FROM site_settings WHERE key = 'schema_version'")
      .first();
    if (Number(row && row.value) >= SCHEMA_VERSION) { SCHEMA_READY = true; return; }
  } catch (e) { /* site_settings not there yet — fall through and migrate */ }

  const migrations = [
    "ALTER TABLE nav_tabs ADD COLUMN fabrics TEXT",
    "ALTER TABLE nav_tabs ADD COLUMN show_in_topbar INTEGER NOT NULL DEFAULT 1",
    "ALTER TABLE orders ADD COLUMN postex_tracking TEXT",
    "ALTER TABLE orders ADD COLUMN postex_status TEXT",
    "ALTER TABLE orders ADD COLUMN postex_booked_at TEXT",
    "ALTER TABLE banners ADD COLUMN device TEXT NOT NULL DEFAULT 'both'",
    "ALTER TABLE orders ADD COLUMN verified_at TEXT",
    "ALTER TABLE orders ADD COLUMN risk_note TEXT",
    `CREATE TABLE IF NOT EXISTS reviews (
       id INTEGER PRIMARY KEY AUTOINCREMENT,
       product_id INTEGER,
       customer_name TEXT NOT NULL,
       city TEXT,
       rating INTEGER NOT NULL DEFAULT 5,
       body TEXT,
       consent INTEGER NOT NULL DEFAULT 0,
       is_approved INTEGER NOT NULL DEFAULT 0,
       sort_order INTEGER NOT NULL DEFAULT 0,
       created_at TEXT DEFAULT (datetime('now'))
     )`,
    "CREATE TABLE IF NOT EXISTS site_settings (key TEXT PRIMARY KEY, value TEXT)",

    /* Indexes. There were none at all, so every category page read every
       row of products, re-read the whole tab-assignment table for each
       one, then sorted the lot in memory — getting linearly slower with
       every product added. The two expression indexes matter because the
       queries compare LOWER(TRIM(fabric)), which a plain column index
       cannot serve. */
    "CREATE INDEX IF NOT EXISTS idx_products_active_sort ON products(is_active, sort_order, id)",
    "CREATE INDEX IF NOT EXISTS idx_products_slug ON products(slug)",
    "CREATE INDEX IF NOT EXISTS idx_products_fabric_norm ON products(LOWER(TRIM(fabric)))",
    "CREATE INDEX IF NOT EXISTS idx_products_fabric_lower ON products(LOWER(fabric))",
    "CREATE INDEX IF NOT EXISTS idx_ntp_tab ON nav_tab_products(tab_id, product_id)",
    "CREATE INDEX IF NOT EXISTS idx_ntp_product ON nav_tab_products(product_id)",
    "CREATE INDEX IF NOT EXISTS idx_navtabs_active ON nav_tabs(is_active, gender, sort_order)",
    "CREATE INDEX IF NOT EXISTS idx_reviews_approved ON reviews(is_approved, product_id)",
    "CREATE INDEX IF NOT EXISTS idx_orders_created ON orders(created_at)"
  ];
  for (const sql of migrations) {
    try { await env.DB.prepare(sql).run(); } catch (e) { /* already there */ }
  }
  try {
    await env.DB.prepare(
      "INSERT OR REPLACE INTO site_settings (key, value) VALUES ('schema_version', ?)"
    ).bind(String(SCHEMA_VERSION)).run();
  } catch (e) {}
  SCHEMA_READY = true;
}

/* Pass ctx from a storefront read and it returns instantly, letting the
   migration finish in the background. Admin and order paths omit ctx and
   wait, because correctness there matters more than a few milliseconds. */
function ensureSchema(env, ctx) {
  if (SCHEMA_READY) return Promise.resolve();
  if (!SCHEMA_RUNNING) SCHEMA_RUNNING = runMigrations(env).catch(() => { SCHEMA_RUNNING = null; });
  if (ctx) { ctx.waitUntil(SCHEMA_RUNNING); return Promise.resolve(); }
  return SCHEMA_RUNNING;
}

/* raw.githubusercontent.com sends Cache-Control: max-age=300 and is not a
   CDN. jsDelivr serves the same repository from a real edge network. Used
   when importing so the fetch itself is fast. */
const GH_RAW_PREFIX = 'https://raw.githubusercontent.com/';
function cdnRewriteServer(u) {
  if (typeof u !== 'string' || u.indexOf(GH_RAW_PREFIX) !== 0) return u;
  const m = u.slice(GH_RAW_PREFIX.length).match(/^([^/]+)\/([^/]+)\/([^/]+)\/(.+)$/);
  return m ? `https://cdn.jsdelivr.net/gh/${m[1]}/${m[2]}@${m[3]}/${m[4]}` : u;
}

// Stable short key from a source URL, so importing twice overwrites.
function hashUrl(s) {
  let h = 5381;
  for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0;
  return (h >>> 0).toString(36) + '-' + (s.length.toString(36));
}

// Pakistani mobile numbers, normalised to 03XXXXXXXXX.
// Returns null when the number can't be a real mobile — the main source of
// junk cash-on-delivery orders.
function normalisePhone(input) {
  let d = String(input || '').replace(/[^0-9]/g, '');
  if (d.startsWith('0092')) d = d.slice(4);
  else if (d.startsWith('92')) d = d.slice(2);
  if (d.startsWith('3')) d = '0' + d;
  return /^03[0-9]{9}$/.test(d) ? d : null;
}

// Collect the fabric names and tab ids behind a set of tabs.
function tabScope(tabs) {
  const ids = tabs.map(t => t.id);
  const fabrics = [];
  tabs.forEach(t => {
    String(t.fabrics || '').split(',').forEach(f => {
      const v = f.trim().toLowerCase();
      if (v && !fabrics.includes(v)) fabrics.push(v);
    });
  });
  return { ids, fabrics };
}

function normaliseFabrics(input) {
  const list = Array.isArray(input) ? input : String(input || '').split(',');
  const clean = list.map(s => String(s).trim()).filter(Boolean);
  return clean.length ? clean.join(',') : null;
}

// Which visitors a banner is for: everyone, big screens only, phones only.
function bannerDevice(v) {
  return ['desktop', 'mobile'].includes(String(v || '')) ? String(v) : 'both';
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' }
  });
}

async function sendWhatsAppAlert(text, env) {
  if (!env.CALLMEBOT_PHONE || !env.CALLMEBOT_APIKEY) return;
  try {
    const url = `https://api.callmebot.com/whatsapp.php?phone=${encodeURIComponent(env.CALLMEBOT_PHONE)}&text=${encodeURIComponent(text)}&apikey=${encodeURIComponent(env.CALLMEBOT_APIKEY)}`;
    await fetch(url);
  } catch (e) {
    // Notification failures should never break order placement
  }
}

async function sendEmailAlert(order, itemsText, env) {
  if (!env.RESEND_API_KEY || !env.NOTIFY_EMAIL) return;
  try {
    await fetch('https://api.resend.com/emails', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${env.RESEND_API_KEY}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        from: 'Calista Orders <onboarding@resend.dev>',
        to: env.NOTIFY_EMAIL,
        subject: `New Order — ${order.customer_name} — Rs. ${order.total}`,
        html: `
          <h2>New Calista Order</h2>
          <p><b>Customer:</b> ${order.customer_name}<br>
          <b>Phone:</b> ${order.phone}<br>
          <b>Address:</b> ${order.address}, ${order.city}</p>
          <p><b>Items:</b><br>${itemsText.replace(/, /g, '<br>')}</p>
          <p><b>Total:</b> Rs. ${order.total}</p>
        `
      })
    });
  } catch (e) {
    // Notification failures should never break order placement
  }
}

async function notifyNewOrder(order, env) {
  const itemsText = order.items.map(i => `${i.name} x${i.qty}`).join(', ');
  const summary = `🛍️ New Calista Order!\nCustomer: ${order.customer_name}\nPhone: ${order.phone}\nAddress: ${order.address}, ${order.city}\nItems: ${itemsText}\nTotal: Rs. ${order.total}`;
  await Promise.allSettled([
    sendWhatsAppAlert(summary, env),
    sendEmailAlert(order, itemsText, env)
  ]);
}

/* ============================================================
   EDGE CACHING
   Cloudflare does not cache Worker responses on its own, so every
   photo request used to mean a Worker invocation plus a fresh R2
   read — the reason the site slowed to a crawl once the catalogue
   grew. These helpers put the CDN in front of both photos and the
   read-only API, and admin writes purge the API keys so edits still
   appear immediately.
   ============================================================ */

// Read-only endpoints safe to serve from the edge for a short window.
const CACHEABLE_API = [
  '/api/bootstrap',
  '/api/products', '/api/banners', '/api/fabric-categories',
  '/api/settings', '/api/nav-tabs', '/api/tiktok-pixel-id',
  '/api/reviews-summary'
];

function isCacheableApi(path) {
  return CACHEABLE_API.includes(path);
}

// Drop every cached variant of the read-only API after an admin write.
async function purgeApiCache(origin) {
  const cache = caches.default;
  const keys = [];
  for (const p of CACHEABLE_API) {
    keys.push(origin + p);
    // Query-string variants the storefront actually requests.
    for (const q of ['?fabric=', '?tab=', '?view=new', '?gender=women', '?gender=men']) {
      keys.push(origin + p + q);
    }
  }
  await Promise.allSettled(keys.map(k => cache.delete(new Request(k))));
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const cache = caches.default;
    const isGet = request.method === 'GET';

    // 1. Serve a cached copy when we have one.
    if (isGet && isCacheableApi(url.pathname)) {
      const hit = await cache.match(request);
      if (hit) return hit;
    }

    const response = await this.handle(request, env, ctx);

    // 2. Store fresh read-only API responses at the edge.
    if (isGet && isCacheableApi(url.pathname) && response.status === 200) {
      const cached = new Response(response.body, response);
      cached.headers.set(
        'Cache-Control',
        'public, max-age=30, s-maxage=120, stale-while-revalidate=600'
      );
      ctx.waitUntil(cache.put(request, cached.clone()));
      return cached;
    }

    // 3. Any admin write invalidates the read-only API immediately.
    if (request.method !== 'GET' && url.pathname.startsWith('/api/admin/')) {
      ctx.waitUntil(purgeApiCache(url.origin));
    }

    return response;
  },

  async handle(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method;

    const isAdminRoute = path === '/admin.html' || path.startsWith('/api/admin/');
    if (isAdminRoute && !checkAdminAuth(request, env)) {
      return unauthorized();
    }

    /* Photos. Three things happen here that did not before:
       • the response is stored in the CDN cache, so the second and every
         later visitor is served from the edge — no Worker→R2 round trip;
       • a returning browser gets a 304 from a HEAD instead of the whole file;
       • a request for "….thumb.webp" that has no thumbnail yet falls back to
         the full photo, so grid thumbnails are safe to link before they exist. */
    if (path.startsWith('/uploads/') && method === 'GET') {
      try {
        const cache = caches.default;
        const hit = await cache.match(request);
        if (hit) return hit;

        let key = decodeURIComponent(path.slice(1));
        let meta = await env.IMAGES.head(key);
        // No thumbnail generated for this photo yet — serve the original.
        // ?exact=1 turns the fallback off, so the admin thumbnail builder can
        // tell which photos still need one.
        const exact = url.searchParams.get('exact') === '1';
        if (!meta && !exact && /\.thumb\.[a-z0-9]{2,5}$/i.test(key)) {
          key = key.replace(/\.thumb(\.[a-z0-9]{2,5})$/i, '$1');
          meta = await env.IMAGES.head(key);
        }
        if (!meta) return new Response('Not found', { status: 404 });

        if (request.headers.get('If-None-Match') === meta.httpEtag) {
          return new Response(null, {
            status: 304,
            headers: {
              'ETag': meta.httpEtag,
              'Cache-Control': 'public, max-age=31536000, immutable'
            }
          });
        }

        const obj = await env.IMAGES.get(key);
        if (!obj) return new Response('Not found', { status: 404 });

        const headers = new Headers();
        obj.writeHttpMetadata(headers);
        headers.set('Content-Type', obj.httpMetadata?.contentType || 'image/jpeg');
        headers.set('Cache-Control', 'public, max-age=31536000, immutable');
        headers.set('ETag', obj.httpEtag);
        headers.set('Accept-Ranges', 'bytes');

        const res = new Response(obj.body, { headers });
        ctx.waitUntil(cache.put(request, res.clone()));
        return res;
      } catch (err) {
        return new Response('Error', { status: 500 });
      }
    }

    /* ---------------- Import outside photos into our own bucket ----------------
       The gents photos were served from raw.githubusercontent.com and the
       ladies photos from a Shopify CDN. Neither can be edge-cached by us, the
       GitHub one is re-downloaded by every browser every five minutes, and
       both leave the catalogue dependent on somebody else's hosting staying
       up. This pulls a batch of them into R2, where they get a one-year
       immutable cache, our own edge caching, and \u2014 once the small-copy
       builder is run \u2014 600px grid versions.

       Called in small batches by the admin page so progress is visible and no
       single request runs long. Idempotent: the key is derived from the source
       URL, so a re-run overwrites rather than duplicating. */
    if (path === '/api/admin/import-images' && method === 'POST') {
      try {
        const { urls } = await request.json();
        if (!Array.isArray(urls) || !urls.length) return json({ error: 'No urls given' }, 400);

        const map = {}, failed = [];
        for (const src of urls.slice(0, 6)) {
          if (typeof src !== 'string' || src.indexOf('http') !== 0) { failed.push(src); continue; }
          try {
            // Fetch through jsDelivr for GitHub-hosted files: same bytes, far faster.
            const res = await fetch(cdnRewriteServer(src), { cf: { cacheTtl: 300 } });
            if (!res.ok) { failed.push(src); continue; }
            const buf = await res.arrayBuffer();
            if (!buf.byteLength) { failed.push(src); continue; }

            const type = res.headers.get('content-type') || 'image/jpeg';
            const ext = (type.match(/image\/(jpeg|jpg|png|webp|gif|avif)/i)?.[1] || 'jpg')
              .toLowerCase().replace('jpeg', 'jpg');
            const key = `uploads/imp-${hashUrl(src)}.${ext}`;

            await env.IMAGES.put(key, buf, { httpMetadata: { contentType: type } });
            map[src] = '/' + key;
          } catch (e) { failed.push(src); }
        }

        // Point the catalogue at the new copies. REPLACE is a no-op on rows
        // that don't contain the old URL, so this is safe to run broadly.
        const stmts = [];
        for (const [src, dest] of Object.entries(map)) {
          stmts.push(env.DB.prepare(
            `UPDATE products SET
               image_url   = REPLACE(image_url, ?, ?),
               image_url_2 = REPLACE(image_url_2, ?, ?),
               images_json = REPLACE(images_json, ?, ?)
             WHERE image_url LIKE ? OR image_url_2 LIKE ? OR images_json LIKE ?`
          ).bind(src, dest, src, dest, src, dest, '%' + src + '%', '%' + src + '%', '%' + src + '%'));
        }
        if (stmts.length) await env.DB.batch(stmts);

        ctx.waitUntil(purgeApiCache(url.origin));
        return json({ imported: Object.keys(map).length, failed: failed.length, map });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/upload' && method === 'POST') {
      try {
        const formData = await request.formData();
        const files = formData.getAll('files');
        // An explicit key lets the admin "build thumbnails" tool write a
        // variant beside an existing photo (…-abc.thumb.webp).
        const explicitKey = formData.get('key');
        const urls = [];
        for (const file of files) {
          if (!file || typeof file === 'string') continue;
          const rawExt = (file.name || '').split('.').pop().toLowerCase();
          const ext = /^[a-z0-9]{2,5}$/.test(rawExt) ? rawExt : 'jpg';
          const key = (typeof explicitKey === 'string' && /^uploads\/[A-Za-z0-9._-]+$/.test(explicitKey))
            ? explicitKey
            : `uploads/${Date.now()}-${Math.random().toString(36).slice(2, 8)}.${ext}`;
          await env.IMAGES.put(key, await file.arrayBuffer(), {
            httpMetadata: { contentType: file.type || 'image/jpeg' }
          });
          urls.push(`/${key}`);
        }
        if (urls.length === 0) return json({ error: 'No files received' }, 400);
        return json({ success: true, urls });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    // Adds the columns this build needs. Safe to run repeatedly: SQLite errors
    // on a duplicate column, which we swallow per statement.
    if (path === '/api/admin/migrate' && method === 'POST') {
      const migrations = [
        "ALTER TABLE nav_tabs ADD COLUMN fabrics TEXT",
        "ALTER TABLE nav_tabs ADD COLUMN show_in_topbar INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE orders ADD COLUMN postex_tracking TEXT",
        "ALTER TABLE orders ADD COLUMN postex_status TEXT",
        "ALTER TABLE orders ADD COLUMN postex_booked_at TEXT",
    "ALTER TABLE banners ADD COLUMN device TEXT NOT NULL DEFAULT 'both'",
        "ALTER TABLE orders ADD COLUMN verified_at TEXT",
        "ALTER TABLE orders ADD COLUMN risk_note TEXT",
        `CREATE TABLE IF NOT EXISTS reviews (
           id INTEGER PRIMARY KEY AUTOINCREMENT,
           product_id INTEGER,
           customer_name TEXT NOT NULL,
           city TEXT,
           rating INTEGER NOT NULL DEFAULT 5,
           body TEXT,
           consent INTEGER NOT NULL DEFAULT 0,
           is_approved INTEGER NOT NULL DEFAULT 0,
           sort_order INTEGER NOT NULL DEFAULT 0,
           created_at TEXT DEFAULT (datetime('now'))
         )`
      ];
      const applied = [], skipped = [];
      for (const sql of migrations) {
        try {
          await env.DB.prepare(sql).run();
          applied.push(sql);
        } catch (err) {
          skipped.push(sql.split('ADD COLUMN ')[1] + ' — already present');
        }
      }
      return json({ success: true, applied, skipped });
    }

    if (path === '/api/admin/orders' && method === 'GET') {
      try {
        await ensureSchema(env);
        const { results } = await env.DB
          .prepare('SELECT * FROM orders ORDER BY created_at DESC')
          .all();
        // Cash-on-delivery history per phone number, so the admin can see at a
        // glance whether a number has refused deliveries before.
        const hist = {};
        (await env.DB.prepare(
          `SELECT phone,
                  COUNT(*) AS orders,
                  SUM(CASE WHEN status = 'delivered' THEN 1 ELSE 0 END) AS delivered,
                  SUM(CASE WHEN status = 'cancelled' THEN 1 ELSE 0 END) AS cancelled
           FROM orders GROUP BY phone`
        ).all()).results.forEach(r => { hist[r.phone] = r; });
        results.forEach(o => {
          const h = hist[o.phone] || {};
          o.phone_orders = h.orders || 1;
          o.phone_delivered = h.delivered || 0;
          o.phone_cancelled = h.cancelled || 0;
        });
        return json(results);
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/orders' && method === 'PATCH') {
      try {
        const { id, status } = await request.json();
        const allowed = ['pending', 'confirmed', 'shipped', 'delivered', 'cancelled'];
        if (!id || !allowed.includes(status)) {
          return json({ error: 'Invalid id or status' }, 400);
        }
        await env.DB.prepare('UPDATE orders SET status = ? WHERE id = ?').bind(status, id).run();
        if (status === 'confirmed') {
          try {
            await env.DB.prepare("UPDATE orders SET verified_at = datetime('now') WHERE id = ? AND verified_at IS NULL").bind(id).run();
          } catch (e) { /* column added on next migrate */ }
        }
        return json({ success: true });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    /* ---------------- Reviews ---------------- */

    // Public: approved reviews for one product (or the whole store when no id).
    if (path === '/api/reviews' && method === 'GET') {
      try {
        await ensureSchema(env, ctx);
        const pid = url.searchParams.get('product_id');
        const q = pid
          ? env.DB.prepare('SELECT id, product_id, customer_name, city, rating, body, created_at FROM reviews WHERE is_approved = 1 AND product_id = ? ORDER BY sort_order ASC, id DESC').bind(pid)
          : env.DB.prepare('SELECT id, product_id, customer_name, city, rating, body, created_at FROM reviews WHERE is_approved = 1 ORDER BY sort_order ASC, id DESC LIMIT 24');
        const { results } = await q.all();
        const count = results.length;
        const avg = count ? results.reduce((s, r) => s + r.rating, 0) / count : 0;
        return json({ reviews: results, count, average: Math.round(avg * 10) / 10 });
      } catch (err) {
        return json({ reviews: [], count: 0, average: 0 });
      }
    }

    // Public: per-product rating totals, for stars on product cards.
    if (path === '/api/reviews-summary' && method === 'GET') {
      try {
        await ensureSchema(env, ctx);
        const { results } = await env.DB.prepare(
          'SELECT product_id, COUNT(*) AS count, AVG(rating) AS average FROM reviews WHERE is_approved = 1 AND product_id IS NOT NULL GROUP BY product_id'
        ).all();
        const map = {};
        results.forEach(r => { map[r.product_id] = { count: r.count, average: Math.round(r.average * 10) / 10 }; });
        return json(map);
      } catch (err) {
        return json({});
      }
    }

    if (path === '/api/admin/reviews' && method === 'GET') {
      try {
        await ensureSchema(env);
        const { results } = await env.DB.prepare(
          `SELECT r.*, p.name AS product_name FROM reviews r
           LEFT JOIN products p ON p.id = r.product_id
           ORDER BY r.is_approved ASC, r.id DESC`
        ).all();
        return json(results);
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/reviews' && (method === 'POST' || method === 'PATCH')) {
      try {
        await ensureSchema(env);
        const r = await request.json();
        const name = String(r.customer_name || '').trim();
        if (!name) return json({ error: 'Customer name is required' }, 400);
        const rating = Math.min(5, Math.max(1, parseInt(r.rating, 10) || 5));
        const consent = r.consent ? 1 : 0;
        // A review can only go live once you've confirmed the customer agreed.
        const approved = (r.is_approved && consent) ? 1 : 0;
        const pid = r.product_id ? parseInt(r.product_id, 10) : null;
        if (method === 'PATCH' && r.id) {
          await env.DB.prepare(
            `UPDATE reviews SET product_id = ?, customer_name = ?, city = ?, rating = ?,
             body = ?, consent = ?, is_approved = ?, sort_order = ? WHERE id = ?`
          ).bind(pid, name, r.city || null, rating, r.body || null, consent, approved,
                 parseInt(r.sort_order, 10) || 0, r.id).run();
        } else {
          await env.DB.prepare(
            `INSERT INTO reviews (product_id, customer_name, city, rating, body, consent, is_approved, sort_order)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
          ).bind(pid, name, r.city || null, rating, r.body || null, consent, approved,
                 parseInt(r.sort_order, 10) || 0).run();
        }
        ctx.waitUntil(purgeApiCache(url.origin));
        return json({ success: true });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/reviews' && method === 'DELETE') {
      try {
        const id = url.searchParams.get('id');
        await env.DB.prepare('DELETE FROM reviews WHERE id = ?').bind(id).run();
        ctx.waitUntil(purgeApiCache(url.origin));
        return json({ success: true });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/products' && method === 'GET') {
      try {
        const { results } = await env.DB
          .prepare('SELECT * FROM products ORDER BY sort_order ASC, id DESC')
          .all();
        return json(results);
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/products' && method === 'POST') {
      try {
        const p = await request.json();
        const images = Array.isArray(p.images) ? p.images.filter(Boolean) : [];
        const imageUrl = images[0] || p.image_url;
        const imageUrl2 = images[1] || p.image_url_2 || null;
        if (!p.name || !p.slug || !p.price || !imageUrl) {
          return json({ error: 'Missing required fields (need at least one image)' }, 400);
        }
        await env.DB.prepare(
          `INSERT INTO products (name, slug, fabric, description, price, sale_price, image_url, image_url_2, images_json, stock, sort_order)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM products))`
        ).bind(
          p.name, p.slug, p.fabric || null, p.description || null,
          p.price, p.sale_price || null, imageUrl, imageUrl2,
          images.length ? JSON.stringify(images) : null, p.stock || 0
        ).run();
        return json({ success: true });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/products/bulk' && method === 'POST') {
      try {
        const { products } = await request.json();
        if (!Array.isArray(products) || products.length === 0) {
          return json({ error: 'No products provided' }, 400);
        }
        const stmts = products.map(p => {
          const imgList = [];
          for (let i = 1; i <= 13; i++) {
            const key = i === 1 ? 'image_url' : `image_url_${i}`;
            if (p[key]) imgList.push(p[key]);
          }
          const imageUrl = imgList[0] || p.image_url || '';
          const imageUrl2 = imgList[1] || p.image_url_2 || null;
          const hasPosition = p.position !== undefined && p.position !== '';
          return env.DB.prepare(
            `INSERT INTO products (name, slug, fabric, description, price, sale_price, image_url, image_url_2, images_json, stock, is_active, sort_order)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ${hasPosition ? '?' : '(SELECT COALESCE(MAX(sort_order), 0) + 1 FROM products)'})
             ON CONFLICT(slug) DO UPDATE SET
               name=excluded.name, fabric=excluded.fabric, description=excluded.description,
               price=excluded.price, sale_price=excluded.sale_price,
               image_url=excluded.image_url, image_url_2=excluded.image_url_2,
               images_json=excluded.images_json, stock=excluded.stock${hasPosition ? ', sort_order=excluded.sort_order' : ''}`
          ).bind(
            ...[
              p.name, p.slug, p.fabric || null, p.description || null,
              Number(p.price), p.sale_price ? Number(p.sale_price) : null,
              imageUrl, imageUrl2, imgList.length ? JSON.stringify(imgList) : null, Number(p.stock) || 0,
              ...(hasPosition ? [Number(p.position)] : [])
            ]
          );
        });
        await env.DB.batch(stmts);
        return json({ success: true, count: products.length });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/products/bulk-tag' && method === 'POST') {
      try {
        const { ids, field, value } = await request.json();
        const allowedFields = ['in_new_arrivals', 'in_sale_tab', 'in_lawn_tab', 'fabric', 'is_active'];
        if (!Array.isArray(ids) || ids.length === 0 || !allowedFields.includes(field)) {
          return json({ error: 'Invalid request' }, 400);
        }
        const stmts = ids.map(id => env.DB.prepare(`UPDATE products SET ${field} = ? WHERE id = ?`).bind(value, id));
        await env.DB.batch(stmts);
        return json({ success: true, count: ids.length });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/products/bulk-delete' && method === 'POST') {
      try {
        const { ids } = await request.json();
        if (!Array.isArray(ids) || ids.length === 0) {
          return json({ error: 'No ids provided' }, 400);
        }
        const stmts = ids.map(id => env.DB.prepare('DELETE FROM products WHERE id = ?').bind(id));
        await env.DB.batch(stmts);
        return json({ success: true, count: ids.length });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/products/reorder' && method === 'POST') {
      try {
        const { updates } = await request.json();
        if (!Array.isArray(updates) || updates.length === 0) {
          return json({ error: 'No updates provided' }, 400);
        }
        const stmts = updates.map(u =>
          env.DB.prepare('UPDATE products SET sort_order = ? WHERE id = ?').bind(Number(u.sort_order), u.id)
        );
        await env.DB.batch(stmts);
        return json({ success: true });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    // Reorder "Shop by Fabric" tiles — lower sort_order shows first.
    if (path === '/api/admin/fabric-categories/reorder' && method === 'POST') {
      try {
        const { updates } = await request.json();
        if (!Array.isArray(updates) || updates.length === 0) {
          return json({ error: 'No updates provided' }, 400);
        }
        const stmts = updates.map(u =>
          env.DB.prepare('UPDATE fabric_categories SET sort_order = ? WHERE id = ?').bind(Number(u.sort_order), u.id)
        );
        await env.DB.batch(stmts);
        return json({ success: true });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    // Reorder homepage banners — lower sort_order shows first.
    if (path === '/api/admin/banners/reorder' && method === 'POST') {
      try {
        const { updates } = await request.json();
        if (!Array.isArray(updates) || updates.length === 0) {
          return json({ error: 'No updates provided' }, 400);
        }
        const stmts = updates.map(u =>
          env.DB.prepare('UPDATE banners SET sort_order = ? WHERE id = ?').bind(Number(u.sort_order), u.id)
        );
        await env.DB.batch(stmts);
        return json({ success: true });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/products' && method === 'DELETE') {
      try {
        const id = url.searchParams.get('id');
        if (!id) return json({ error: 'Missing id' }, 400);
        await env.DB.prepare('DELETE FROM products WHERE id = ?').bind(id).run();
        return json({ success: true });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/subscribers' && method === 'GET') {
      try {
        const { results } = await env.DB
          .prepare('SELECT * FROM subscribers ORDER BY created_at DESC')
          .all();
        return json(results);
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/fabric-categories' && method === 'GET') {
      try {
        const { results } = await env.DB.prepare('SELECT * FROM fabric_categories ORDER BY sort_order ASC').all();
        return json(results);
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/fabric-categories' && method === 'GET') {
      try {
        const { results } = await env.DB.prepare('SELECT * FROM fabric_categories ORDER BY sort_order ASC').all();
        return json(results);
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/fabric-categories' && method === 'POST') {
      try {
        const { name } = await request.json();
        if (!name || !name.trim()) return json({ error: 'Name is required' }, 400);
        const cleanName = name.trim();

        // Idempotent: if this fabric already exists (case-insensitive), just return it —
        // this lets the product uploader "auto-categorize" a fabric without erroring
        // when the category already exists.
        const existing = await env.DB.prepare(
          'SELECT * FROM fabric_categories WHERE LOWER(name) = LOWER(?)'
        ).bind(cleanName).first();
        if (existing) return json({ success: true, created: false, fabric: existing });

        const ICONS = ['swatch', 'roll', 'thread', 'fold', 'loom', 'pattern', 'stitch', 'drape'];
        const icon = ICONS[Math.floor(Math.random() * ICONS.length)];

        await env.DB.prepare(
          `INSERT INTO fabric_categories (name, sort_order, icon)
           VALUES (?, (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM fabric_categories), ?)`
        ).bind(cleanName, icon).run();
        const created = await env.DB.prepare('SELECT * FROM fabric_categories WHERE LOWER(name) = LOWER(?)').bind(cleanName).first();
        return json({ success: true, created: true, fabric: created });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/fabric-categories' && method === 'DELETE') {
      try {
        const id = url.searchParams.get('id');
        if (!id) return json({ error: 'Missing id' }, 400);
        await env.DB.prepare('DELETE FROM fabric_categories WHERE id = ?').bind(id).run();
        return json({ success: true });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/discounts' && method === 'GET') {
      try {
        const { results } = await env.DB.prepare('SELECT * FROM discounts ORDER BY created_at DESC').all();
        return json(results);
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/discounts' && method === 'POST') {
      try {
        const d = await request.json();
        if (!d.title || !d.type) return json({ error: 'Title and type are required' }, 400);
        await env.DB.prepare(
          `INSERT INTO discounts (code, title, type, value, buy_quantity, get_quantity, get_discount_percent, min_cart_value, usage_limit, start_date, end_date, is_active)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
        ).bind(
          d.code || null, d.title, d.type, d.value || null, d.buy_quantity || null, d.get_quantity || null,
          d.get_discount_percent ?? 100, d.min_cart_value || null, d.usage_limit || null,
          d.start_date || null, d.end_date || null, d.is_active === false ? 0 : 1
        ).run();
        return json({ success: true });
      } catch (err) {
        if (String(err.message).includes('UNIQUE')) return json({ error: 'That discount code already exists' }, 400);
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/discounts' && method === 'PATCH') {
      try {
        const d = await request.json();
        if (!d.id) return json({ error: 'Missing id' }, 400);
        await env.DB.prepare(
          `UPDATE discounts SET code=?, title=?, type=?, value=?, buy_quantity=?, get_quantity=?, get_discount_percent=?,
           min_cart_value=?, usage_limit=?, start_date=?, end_date=?, is_active=? WHERE id=?`
        ).bind(
          d.code || null, d.title, d.type, d.value || null, d.buy_quantity || null, d.get_quantity || null,
          d.get_discount_percent ?? 100, d.min_cart_value || null, d.usage_limit || null,
          d.start_date || null, d.end_date || null, d.is_active === false ? 0 : 1, d.id
        ).run();
        return json({ success: true });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/discounts' && method === 'DELETE') {
      try {
        const id = url.searchParams.get('id');
        if (!id) return json({ error: 'Missing id' }, 400);
        await env.DB.prepare('DELETE FROM discounts WHERE id = ?').bind(id).run();
        return json({ success: true });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    // Public: automatic (codeless) active discounts, for auto-applying in cart
    if (path === '/api/active-promotions' && method === 'GET') {
      try {
        const today = new Date().toISOString().slice(0, 10);
        const { results } = await env.DB.prepare(
          `SELECT * FROM discounts WHERE is_active = 1 AND code IS NULL
           AND (start_date IS NULL OR start_date <= ?) AND (end_date IS NULL OR end_date >= ?)`
        ).bind(today, today).all();
        return json(results);
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    // Public: validate + calculate a discount for the current cart
    if (path === '/api/apply-discount' && method === 'POST') {
      try {
        const { code, cart } = await request.json();
        if (!code) return json({ error: 'Enter a discount code' }, 400);
        const discount = await env.DB.prepare('SELECT * FROM discounts WHERE code = ? AND is_active = 1').bind(code.trim().toUpperCase()).first();
        if (!discount) return json({ error: 'Invalid or expired code' }, 400);

        const today = new Date().toISOString().slice(0, 10);
        if (discount.start_date && discount.start_date > today) return json({ error: 'This code is not active yet' }, 400);
        if (discount.end_date && discount.end_date < today) return json({ error: 'This code has expired' }, 400);
        if (discount.usage_limit && discount.used_count >= discount.usage_limit) return json({ error: 'This code has reached its usage limit' }, 400);

        const result = calculateDiscount(discount, cart);
        if (result.error) return json({ error: result.error }, 400);
        return json({ success: true, discount_amount: result.amount, title: discount.title, code: discount.code });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/subscribe' && method === 'POST') {
      try {
        const { email } = await request.json();
        const valid = typeof email === 'string' && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
        if (!valid) return json({ error: 'Please enter a valid email address' }, 400);
        try {
          await env.DB.prepare('INSERT INTO subscribers (email) VALUES (?)').bind(email).run();
        } catch (dbErr) {
          if (String(dbErr.message).includes('UNIQUE')) {
            return json({ error: "You're already subscribed!" }, 400);
          }
          throw dbErr;
        }
        return json({ success: true });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/banners' && method === 'GET') {
      try {
        const { results } = await env.DB
          .prepare('SELECT * FROM banners WHERE is_active = 1 ORDER BY sort_order ASC')
          .all();
        return json(results);
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/banners' && method === 'GET') {
      try {
        const { results } = await env.DB
          .prepare('SELECT * FROM banners ORDER BY sort_order ASC')
          .all();
        return json(results);
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/banners' && method === 'POST') {
      try {
        const b = await request.json();
        // A banner needs either wording or artwork — a blank heading with an
        // uploaded image is the "image only" banner.
        if (!b.heading && !b.image_url && !b.image_url_mobile && !b.mobile_fabric_source) {
          return json({ error: 'Add a heading or a banner image' }, 400);
        }
        await env.DB.prepare(
          `INSERT INTO banners (eyebrow, heading, subheading, button_text, button_link, image_url, image_url_mobile, mobile_fabric_source, device, sort_order)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
        ).bind(
          b.eyebrow || null, b.heading || '', b.subheading || null,
          b.button_text || null, b.button_link || null, b.image_url || null, b.image_url_mobile || null,
          b.mobile_fabric_source || null, bannerDevice(b.device), Number(b.sort_order) || 0
        ).run();
        return json({ success: true });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/banners' && method === 'PATCH') {
      try {
        const b = await request.json();
        if (!b.id) return json({ error: 'Missing id' }, 400);
        if (!b.heading && !b.image_url && !b.image_url_mobile && !b.mobile_fabric_source) {
          return json({ error: 'Add a heading or a banner image' }, 400);
        }
        await env.DB.prepare(
          `UPDATE banners SET eyebrow=?, heading=?, subheading=?, button_text=?, button_link=?, image_url=?, image_url_mobile=?, mobile_fabric_source=?, device=?, sort_order=?, is_active=?
           WHERE id=?`
        ).bind(
          b.eyebrow || null, b.heading || '', b.subheading || null,
          b.button_text || null, b.button_link || null, b.image_url || null, b.image_url_mobile || null,
          b.mobile_fabric_source || null, bannerDevice(b.device), Number(b.sort_order) || 0,
          b.is_active === false ? 0 : 1, b.id
        ).run();
        return json({ success: true });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/banners' && method === 'DELETE') {
      try {
        const id = url.searchParams.get('id');
        if (!id) return json({ error: 'Missing id' }, 400);
        await env.DB.prepare('DELETE FROM banners WHERE id = ?').bind(id).run();
        return json({ success: true });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/settings' && method === 'GET') {
      try {
        const { results } = await env.DB.prepare('SELECT key, value FROM site_settings').all();
        const settings = {};
        // Courier credentials live in the same table but must never reach the
        // storefront — this endpoint is public.
        const SECRET_PREFIXES = ['postex_'];
        results.forEach(r => {
          if (SECRET_PREFIXES.some(p => r.key.startsWith(p))) return;
          settings[r.key] = r.value;
        });
        return json(settings);
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    // Tiny, cacheable endpoint used by the TikTok pixel snippet on every page —
    // deliberately separate from /api/settings so it stays fast and the pixel
    // ID can be changed from the admin panel without a code redeploy.
    if (path === '/api/tiktok-pixel-id' && method === 'GET') {
      try {
        const row = await env.DB.prepare("SELECT value FROM site_settings WHERE key = 'tiktok_pixel_id'").first();
        return new Response(JSON.stringify({ id: row ? row.value : null }), {
          headers: {
            'Content-Type': 'application/json',
            'Cache-Control': 'public, max-age=300'
          }
        });
      } catch (err) {
        return json({ id: null });
      }
    }

    /* ============================================================
       PostEx — direct order booking (Shopify-app style)
       ------------------------------------------------------------
       The merchant API token and default pickup address code are
       stored in site_settings (postex_token / postex_pickup_code) and
       never sent to the storefront. Booking an order calls PostEx's
       create-order endpoint and stores the returned tracking number
       on the order row, so the admin never retypes an order.
       Docs: api.postex.pk/services/integration/api/order/*
       ============================================================ */
    if (path.startsWith('/api/admin/postex/')) {
      const POSTEX_BASE = 'https://api.postex.pk/services/integration/api/order';

      const getToken = async () => {
        const row = await env.DB
          .prepare("SELECT value FROM site_settings WHERE key = 'postex_token'").first();
        return row && row.value ? row.value.trim() : null;
      };

      const postexFetch = async (endpoint, init = {}) => {
        const token = await getToken();
        if (!token) {
          return { ok: false, error: 'No PostEx API token saved yet. Add it under Shipping in the admin panel.' };
        }
        try {
          const res = await fetch(POSTEX_BASE + endpoint, {
            ...init,
            headers: { token, 'Content-Type': 'application/json', ...(init.headers || {}) }
          });
          const text = await res.text();
          let data = null;
          try { data = JSON.parse(text); } catch (e) { data = { statusMessage: text }; }
          if (!res.ok) {
            return { ok: false, error: (data && (data.statusMessage || data.message)) || ('PostEx returned ' + res.status), data };
          }
          return { ok: true, data };
        } catch (err) {
          return { ok: false, error: 'Could not reach PostEx: ' + err.message };
        }
      };

      // Cities PostEx delivers to — used to populate the booking dropdown.
      if (path === '/api/admin/postex/cities' && method === 'GET') {
        const r = await postexFetch('/v2/get-operational-city?operationalCityType=Delivery');
        if (!r.ok) return json({ error: r.error }, 400);
        const list = (r.data && (r.data.dist || r.data.data)) || [];
        const names = list
          .map(c => c.operationalCityName || c.cityName || c.name)
          .filter(Boolean)
          .sort((a, b) => a.localeCompare(b));
        return json({ cities: names });
      }

      // The merchant's registered pickup addresses.
      if (path === '/api/admin/postex/addresses' && method === 'GET') {
        const r = await postexFetch('/v1/get-merchant-address');
        if (!r.ok) return json({ error: r.error }, 400);
        const list = (r.data && (r.data.dist || r.data.data)) || [];
        return json({
          addresses: list.map(a => ({
            code: a.addressCode || a.pickupAddressCode || a.code,
            city: a.cityName || a.city,
            address: a.address
          })).filter(a => a.code)
        });
      }

      // Book one order. Everything except city/pieces/amount comes from the
      // order row, so the admin clicks once.
      if (path === '/api/admin/postex/book' && method === 'POST') {
        try {
          const body = await request.json();
          const order = await env.DB
            .prepare('SELECT * FROM orders WHERE id = ?').bind(body.order_id).first();
          if (!order) return json({ error: 'Order not found' }, 404);
          if (order.postex_tracking) {
            return json({ error: 'Already booked with PostEx (' + order.postex_tracking + ')' }, 400);
          }

          let items = [];
          try { items = JSON.parse(order.items_json) || []; } catch (e) {}
          const pieces = Number(body.items) > 0
            ? Number(body.items)
            : Math.max(1, items.reduce((s, i) => s + (Number(i.qty) || 1), 0));

          const detail = items.length
            ? items.map(i => `${i.name} x${i.qty || 1}`).join(', ').slice(0, 240)
            : ('Order #' + order.id);

          const pickupRow = await env.DB
            .prepare("SELECT value FROM site_settings WHERE key = 'postex_pickup_code'").first();

          const payload = {
            cityName: (body.city || order.city || '').trim(),
            customerName: order.customer_name,
            // PostEx wants 03xxxxxxxxx
            customerPhone: String(order.phone || '').replace(/[^0-9]/g, '').replace(/^92/, '0').slice(0, 11),
            deliveryAddress: order.address,
            invoiceDivision: 1,
            invoicePayment: body.prepaid ? 0 : Number(body.amount ?? order.total),
            items: pieces,
            orderDetail: detail,
            orderRefNumber: 'CAL-' + order.id,
            orderType: 'Normal',
            transactionNotes: (body.notes || '').slice(0, 240)
          };
          if (pickupRow && pickupRow.value) payload.pickupAddressCode = pickupRow.value.trim();

          const r = await postexFetch('/v3/create-order', {
            method: 'POST', body: JSON.stringify(payload)
          });
          if (!r.ok) return json({ error: r.error }, 400);

          const dist = (r.data && r.data.dist) || {};
          const tracking = dist.trackingNumber || dist.trackingNo || null;
          if (!tracking) {
            return json({ error: (r.data && r.data.statusMessage) || 'PostEx did not return a tracking number', raw: r.data }, 400);
          }

          await env.DB.prepare(
            `UPDATE orders SET postex_tracking = ?, postex_status = ?, postex_booked_at = datetime('now'),
                                status = CASE WHEN status = 'pending' THEN 'confirmed' ELSE status END
             WHERE id = ?`
          ).bind(tracking, 'Booked', order.id).run();

          return json({ success: true, tracking, order_id: order.id });
        } catch (err) {
          return json({ error: err.message }, 500);
        }
      }

      // Refresh the delivery status of one booked order.
      if (path === '/api/admin/postex/track' && method === 'GET') {
        const tracking = url.searchParams.get('tracking');
        if (!tracking) return json({ error: 'No tracking number' }, 400);
        const r = await postexFetch('/v1/track-order/' + encodeURIComponent(tracking));
        if (!r.ok) return json({ error: r.error }, 400);
        const d = (r.data && r.data.dist) || {};
        const status = d.transactionStatus || d.orderStatus || d.status || null;
        if (status) {
          await env.DB.prepare('UPDATE orders SET postex_status = ? WHERE postex_tracking = ?')
            .bind(status, tracking).run();
        }
        return json({ success: true, status, history: d.transactionStatusHistory || d.statusHistory || [] });
      }

      if (path === '/api/admin/postex/cancel' && method === 'POST') {
        try {
          const { tracking } = await request.json();
          if (!tracking) return json({ error: 'No tracking number' }, 400);
          const r = await postexFetch('/v1/cancel-order', {
            method: 'PUT', body: JSON.stringify({ trackingNumber: tracking })
          });
          if (!r.ok) return json({ error: r.error }, 400);
          await env.DB.prepare(
            "UPDATE orders SET postex_status = 'Cancelled', postex_tracking = NULL WHERE postex_tracking = ?"
          ).bind(tracking).run();
          return json({ success: true });
        } catch (err) {
          return json({ error: err.message }, 500);
        }
      }

      return json({ error: 'Unknown PostEx endpoint' }, 404);
    }

    if (path === '/api/admin/settings' && method === 'GET') {
      try {
        const { results } = await env.DB.prepare('SELECT key, value FROM site_settings').all();
        const settings = {};
        results.forEach(r => { settings[r.key] = r.value; });
        return json(settings);
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/settings' && method === 'POST') {
      try {
        const body = await request.json();
        const entries = Object.entries(body);
        const stmts = entries.map(([k, v]) =>
          env.DB.prepare('INSERT INTO site_settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value').bind(k, v)
        );
        await env.DB.batch(stmts);
        return json({ success: true });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    /* ONE request, ONE D1 batch, everything the storefront needs to paint.
       The pages used to fire seven separate requests (products, banners,
       fabrics, settings, nav tabs, review totals, pixel id) and each one was
       its own Worker invocation and its own database round trip. That is what
       made browsing feel slow, and it is why the site felt slower the more
       stock was added. Now it is one call. */
    if (path === '/api/bootstrap' && method === 'GET') {
      try {
        await ensureSchema(env, ctx);
        const rs = await env.DB.batch([
          env.DB.prepare(`SELECT ${CARD_COLS} FROM products p WHERE p.is_active = 1 ORDER BY p.sort_order ASC, p.id DESC`),
          env.DB.prepare('SELECT * FROM banners WHERE is_active = 1 ORDER BY sort_order ASC'),
          env.DB.prepare('SELECT * FROM fabric_categories ORDER BY sort_order ASC'),
          env.DB.prepare('SELECT key, value FROM site_settings'),
          env.DB.prepare("SELECT * FROM nav_tabs WHERE is_active = 1 ORDER BY gender ASC, sort_order ASC"),
          env.DB.prepare('SELECT product_id, COUNT(*) AS count, AVG(rating) AS average FROM reviews WHERE is_approved = 1 AND product_id IS NOT NULL GROUP BY product_id')
        ]);
        const settings = {};
        (rs[3].results || []).forEach(r => { settings[r.key] = r.value; });
        const reviews = {};
        (rs[5].results || []).forEach(r => {
          reviews[r.product_id] = { count: r.count, average: Math.round(r.average * 10) / 10 };
        });
        return json({
          products: rs[0].results || [],
          banners: rs[1].results || [],
          fabrics: rs[2].results || [],
          settings,
          navTabs: rs[4].results || [],
          reviews,
          pixel: settings.tiktok_pixel_id || null
        });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/products' && method === 'GET') {
      try {
        await ensureSchema(env, ctx);

        /* A category tab fills itself two ways, and we return the union:
           1. products the admin ticked for it (nav_tab_products), and
           2. every product whose fabric is in the tab's fabric list
              (nav_tabs.fabrics) — so Summer = Lawn, Winter = Dhanak +
              Karandi, Intermix = Cambric + Silk stay correct as stock is
              added, with no re-tagging. */
        const tabSlug = url.searchParams.get('tab');
        if (tabSlug) {
          const tab = await env.DB
            .prepare('SELECT * FROM nav_tabs WHERE slug = ? AND is_active = 1')
            .bind(tabSlug).first();
          if (!tab) return json([]);
          const { ids, fabrics } = tabScope([tab]);
          return json(await productsInScope(env, ids, fabrics, false));
        }

        // A top-bar audience (Ladies / Gents / anything added later): every
        // product reachable through that audience's category tabs.
        const genderParam = url.searchParams.get('gender');
        if (genderParam) {
          const { results: tabs } = await env.DB
            .prepare("SELECT * FROM nav_tabs WHERE is_active = 1 AND (gender = ? OR gender = 'all')")
            .bind(genderParam).all();
          const { ids, fabrics } = tabScope(tabs || []);
          // No tabs configured for this audience yet — show everything rather
          // than an empty shop.
          return json(await productsInScope(env, ids, fabrics, true));
        }

        const fabric = url.searchParams.get('fabric');
        if (fabric) {
          const { results } = await env.DB
            .prepare(`SELECT ${CARD_COLS} FROM products p WHERE p.is_active = 1 AND LOWER(p.fabric) = LOWER(?) ORDER BY p.sort_order ASC, p.id DESC`)
            .bind(fabric)
            .all();
          return json(results);
        }
        const { results } = await env.DB
          .prepare(`SELECT ${CARD_COLS} FROM products p WHERE p.is_active = 1 ORDER BY p.sort_order ASC, p.id DESC`)
          .all();
        return json(results);
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    /* ---------------- Nav tabs (hamburger menu: Men / Women categories) ---------------- */
    if (path === '/api/nav-tabs' && method === 'GET') {
      try {
        await ensureSchema(env, ctx);
        const { results } = await env.DB
          .prepare('SELECT * FROM nav_tabs WHERE is_active = 1 ORDER BY gender ASC, sort_order ASC')
          .all();
        return json(results);
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/nav-tabs' && method === 'GET') {
      try {
        await ensureSchema(env);
        const { results } = await env.DB
          .prepare('SELECT * FROM nav_tabs ORDER BY gender ASC, sort_order ASC')
          .all();
        return json(results);
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/nav-tabs' && method === 'POST') {
      try {
        await ensureSchema(env);
        const t = await request.json();
        if (!t.label || !t.slug) return json({ error: 'Label and slug are required' }, 400);
        const gender = ['men', 'women', 'all'].includes(t.gender) ? t.gender : 'women';
        const fabrics = normaliseFabrics(t.fabrics);
        const topbar = t.show_in_topbar === false || t.show_in_topbar === 0 ? 0 : 1;
        try {
          await env.DB.prepare(
            `INSERT INTO nav_tabs (label, slug, gender, fabrics, show_in_topbar, sort_order)
             VALUES (?, ?, ?, ?, ?, (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM nav_tabs WHERE gender = ?))`
          ).bind(t.label.trim(), t.slug.trim().toLowerCase(), gender, fabrics, topbar, gender).run();
        } catch (colErr) {
          // Migration not run yet — fall back to the original columns.
          await env.DB.prepare(
            `INSERT INTO nav_tabs (label, slug, gender, sort_order)
             VALUES (?, ?, ?, (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM nav_tabs WHERE gender = ?))`
          ).bind(t.label.trim(), t.slug.trim().toLowerCase(), gender, gender).run();
        }
        return json({ success: true });
      } catch (err) {
        if (String(err.message).includes('UNIQUE')) return json({ error: 'That slug already exists — choose a different tab name' }, 400);
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/nav-tabs' && method === 'PATCH') {
      try {
        await ensureSchema(env);
        const t = await request.json();
        if (!t.id || !t.label || !t.slug) return json({ error: 'Missing id, label or slug' }, 400);
        const gender = ['men', 'women', 'all'].includes(t.gender) ? t.gender : 'women';
        try {
          await env.DB.prepare(
            `UPDATE nav_tabs SET label=?, slug=?, gender=?, fabrics=?, show_in_topbar=? WHERE id=?`
          ).bind(
            t.label.trim(), t.slug.trim().toLowerCase(), gender,
            normaliseFabrics(t.fabrics),
            t.show_in_topbar === false || t.show_in_topbar === 0 ? 0 : 1,
            t.id
          ).run();
        } catch (colErr) { /* migration not run yet */ }
        await env.DB.prepare(
          `UPDATE nav_tabs SET label=?, slug=?, gender=?, sort_order=?, is_active=? WHERE id=?`
        ).bind(
          t.label.trim(), t.slug.trim().toLowerCase(), gender, Number(t.sort_order) || 0,
          t.is_active === false ? 0 : 1, t.id
        ).run();
        return json({ success: true });
      } catch (err) {
        if (String(err.message).includes('UNIQUE')) return json({ error: 'That slug already exists — choose a different tab name' }, 400);
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/nav-tabs' && method === 'DELETE') {
      try {
        const id = url.searchParams.get('id');
        if (!id) return json({ error: 'Missing id' }, 400);
        await env.DB.batch([
          env.DB.prepare('DELETE FROM nav_tab_products WHERE tab_id = ?').bind(id),
          env.DB.prepare('DELETE FROM nav_tabs WHERE id = ?').bind(id)
        ]);
        return json({ success: true });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    // Live count of what each tab will actually show on the site.
    if (path === '/api/admin/nav-tabs/counts' && method === 'GET') {
      try {
        await ensureSchema(env);
        const { results: tabs } = await env.DB.prepare('SELECT * FROM nav_tabs').all();
        const counts = {};
        for (const t of tabs || []) {
          const { ids, fabrics } = tabScope([t]);
          const rows = await productsInScope(env, ids, fabrics, false);
          counts[t.id] = rows.length;
        }
        return json(counts);
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    // Get product ids currently assigned to a tab, for the admin checklist UI
    if (path === '/api/admin/nav-tabs/products' && method === 'GET') {
      try {
        const tabId = url.searchParams.get('tab_id');
        if (!tabId) return json({ error: 'Missing tab_id' }, 400);
        const { results } = await env.DB.prepare('SELECT product_id FROM nav_tab_products WHERE tab_id = ?').bind(tabId).all();
        return json(results.map(r => r.product_id));
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    // Replace the full set of products assigned to a tab
    if (path === '/api/admin/nav-tabs/assign' && method === 'POST') {
      try {
        const { tab_id, product_ids } = await request.json();
        if (!tab_id || !Array.isArray(product_ids)) return json({ error: 'Missing tab_id or product_ids' }, 400);
        const stmts = [env.DB.prepare('DELETE FROM nav_tab_products WHERE tab_id = ?').bind(tab_id)];
        product_ids.forEach(pid => {
          stmts.push(env.DB.prepare('INSERT INTO nav_tab_products (tab_id, product_id) VALUES (?, ?)').bind(tab_id, pid));
        });
        await env.DB.batch(stmts);
        return json({ success: true, count: product_ids.length });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/order' && method === 'POST') {
      try {
        await ensureSchema(env);
        const body = await request.json();
        const { customer_name, phone, address, city, items, discount_code } = body;

        if (!customer_name || !phone || !address || !city || !items || !items.length) {
          return json({ error: 'Missing required fields' }, 400);
        }

        // Cash on delivery only works if the number is reachable.
        const cleanPhone = normalisePhone(phone);
        if (!cleanPhone) {
          return json({ error: 'Please enter a valid Pakistani mobile number, e.g. 0300 1234567' }, 400);
        }
        if (String(address).trim().length < 10) {
          return json({ error: 'Please enter a complete address so the courier can find you' }, 400);
        }

        // Same number, same total, within 10 minutes = a double tap, not a
        // second order. Return the first one instead of booking twice.
        const dupe = await env.DB.prepare(
          "SELECT id FROM orders WHERE phone = ? AND created_at > datetime('now', '-10 minutes') ORDER BY id DESC LIMIT 1"
        ).bind(cleanPhone).first();

        /* Prices come from the database, never from the browser.
           The cart lives in localStorage, so a client-sent price is only ever
           a suggestion — anyone can edit it. Every line is re-priced here from
           the products table, and an item that has been deactivated or gone
           out of stock stops the order instead of being booked. */
        const wanted = [];
        const ids = [];
        for (const i of items) {
          const pid = parseInt(i.product_id, 10);
          if (!pid) return json({ error: 'Your bag is out of date — please reload the page and try again.' }, 400);
          if (ids.indexOf(pid) === -1) ids.push(pid);
          wanted.push({ pid, size: i.size || null, qty: Math.max(1, Math.min(20, parseInt(i.qty, 10) || 1)) });
        }
        const { results: rows } = await env.DB.prepare(
          `SELECT id, name, price, sale_price, stock, is_active FROM products WHERE id IN (${ids.map(() => '?').join(',')})`
        ).bind(...ids).all();
        const byId = {};
        (rows || []).forEach(r => { byId[r.id] = r; });

        const priced = [];
        for (const w of wanted) {
          const p = byId[w.pid];
          if (!p || !p.is_active) {
            return json({ error: 'One of the items in your bag is no longer available. Please remove it and try again.' }, 400);
          }
          const inStock = (p.stock === null || p.stock === undefined) ? Infinity : Number(p.stock);
          if (inStock < w.qty) {
            return json({
              error: inStock <= 0
                ? `${p.name} has just sold out. Please remove it from your bag.`
                : `Only ${inStock} left of ${p.name}. Please lower the quantity.`
            }, 400);
          }
          const unit = (p.sale_price !== null && p.sale_price !== undefined && Number(p.sale_price) > 0)
            ? Number(p.sale_price) : Number(p.price);
          priced.push({ product_id: p.id, name: p.name, size: w.size, qty: w.qty, price: unit });
        }

        let subtotal = priced.reduce((sum, i) => sum + i.price * i.qty, 0);
        let discountAmount = 0;
        let appliedCode = null;

        if (discount_code) {
          const discount = await env.DB.prepare('SELECT * FROM discounts WHERE code = ? AND is_active = 1').bind(discount_code.trim().toUpperCase()).first();
          if (discount) {
            const result = calculateDiscount(discount, priced);
            if (!result.error) {
              discountAmount = result.amount;
              appliedCode = discount.code;
              ctx.waitUntil(env.DB.prepare('UPDATE discounts SET used_count = used_count + 1 WHERE id = ?').bind(discount.id).run());
            }
          }
        }

        const total = Math.max(0, subtotal - discountAmount);

        if (dupe) {
          const prev = await env.DB.prepare('SELECT total, items_json FROM orders WHERE id = ?').bind(dupe.id).first();
          if (prev && prev.total === total && prev.items_json === JSON.stringify(priced)) {
            return json({ success: true, total, discount_amount: discountAmount, duplicate: true });
          }
        }

        // Flag for the admin: this number has refused deliveries before.
        let riskNote = null;
        try {
          const h = await env.DB.prepare(
            "SELECT COUNT(*) AS n, SUM(CASE WHEN status = 'cancelled' THEN 1 ELSE 0 END) AS c FROM orders WHERE phone = ?"
          ).bind(cleanPhone).first();
          if (h && h.c > 0) riskNote = `${h.c} of ${h.n} previous orders cancelled`;
        } catch (e) { /* non-fatal */ }

        await env.DB.prepare(
          `INSERT INTO orders (customer_name, phone, address, city, items_json, total, discount_code, discount_amount, status, risk_note)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)`
        ).bind(customer_name, cleanPhone, address, city, JSON.stringify(priced), total, appliedCode, discountAmount, riskNote).run();

        // Stock comes down as orders come in, so "only N left" stays true and
        // two people can't be sold the last piece.
        ctx.waitUntil(env.DB.batch(priced.map(i =>
          env.DB.prepare('UPDATE products SET stock = MAX(0, stock - ?) WHERE id = ? AND stock IS NOT NULL').bind(i.qty, i.product_id)
        )).then(() => purgeApiCache(url.origin)).catch(() => {}));

        ctx.waitUntil(notifyNewOrder({ customer_name, phone: cleanPhone, address, city, items: priced, total, risk_note: riskNote }, env));

        return json({ success: true, total, discount_amount: discountAmount });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/sitemap.xml' && method === 'GET') {
      try {
        const { results } = await env.DB.prepare('SELECT slug FROM products WHERE is_active = 1').all();
        const origin = url.origin;
        const staticUrls = ['/', '/collection.html', '/cart.html'];
        const urls = [
          ...staticUrls.map(p => `<url><loc>${origin}${p}</loc></url>`),
          ...results.map(r => `<url><loc>${origin}/product.html?slug=${r.slug}</loc></url>`)
        ];
        const xml = `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls.join('\n')}\n</urlset>`;
        return new Response(xml, { headers: { 'Content-Type': 'application/xml' } });
      } catch (err) {
        return new Response('', { status: 500 });
      }
    }

    if (path === '/robots.txt' && method === 'GET') {
      const body = `User-agent: *\nAllow: /\nDisallow: /admin.html\nDisallow: /api/\nSitemap: ${url.origin}/sitemap.xml\n`;
      return new Response(body, { headers: { 'Content-Type': 'text/plain' } });
    }

    return env.ASSETS.fetch(request);
  }
};
