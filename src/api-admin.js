/**
 * Calista merchant app — admin API for Cloudflare Workers / Pages Functions.
 *
 * This file is the ONLY thing that stands between the Android app and your D1
 * database. Nothing else in your website changes.
 *
 * ── THE ONE THING YOU MUST EDIT ─────────────────────────────────────────────
 * The COL / PCOL maps below. Left side = what the app expects (never change it).
 * Right side = the real column name in YOUR D1 tables (change these to match).
 * Get your real names with:
 *   SELECT group_concat(name, ', ') FROM pragma_table_info('orders');
 *   SELECT group_concat(name, ', ') FROM pragma_table_info('products');
 * ────────────────────────────────────────────────────────────────────────────
 */

const COL = {
  id: 'id',
  name: 'customer_name',
  phone: 'phone',
  city: 'city',
  address: 'address',
  status: 'status',
  total: 'total',
  created_at: 'created_at',
  items: 'items_json',
  updated_at: 'updated_at',
  courier: 'courier',
  tracking: 'tracking',
  wa_status: 'wa_status',
  wa_last_sent: 'wa_last_sent',
};

// Your orders table has no payment column - every order is Cash on Delivery.
const PAYMENT_LITERAL = "'COD'";

const PCOL = {
  id: 'id',
  name: 'name',
  fabric: 'fabric',
  price: 'price',
  sale_price: 'sale_price',
  stock: 'stock',
  active: 'is_active',
  image: 'image_url',
};

const ORDERS = 'orders';
const PRODUCTS = 'products';

// ── helpers ────────────────────────────────────────────────────────────────

const json = (data, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8' },
  });

// The app sends "Authorization: Bearer <token>". The website's own admin page sends
// "Basic ..." - a different scheme, so the two never collide.
// Accepts ADMIN_TOKEN if you set one; otherwise your existing ADMIN_PASSWORD works.
const authed = (request, env) => {
  const h = request.headers.get('authorization') || '';
  if (!h.startsWith('Bearer ')) return false;
  const token = h.slice(7).trim();
  if (!token) return false;
  return token === env.ADMIN_TOKEN || token === env.ADMIN_PASSWORD;
};

const now = () => new Date().toISOString().replace('T', ' ').slice(0, 19);

const orderSelect = `
  SELECT ${COL.id} AS id, ${COL.name} AS name, ${COL.phone} AS phone,
         ${COL.city} AS city, ${COL.address} AS address, ${COL.status} AS status,
         ${PAYMENT_LITERAL} AS payment, ${COL.total} AS total,
         ${COL.created_at} AS created_at, ${COL.items} AS items,
         ${COL.courier} AS courier, ${COL.tracking} AS tracking,
         ${COL.wa_status} AS wa_status
  FROM ${ORDERS}`;

/**
 * Handles every /api/admin/* request.
 * Returns a Response, or null when the path is not ours (so your existing
 * website routing carries on untouched).
 */
export async function handleAdmin(request, env) {
  const url = new URL(request.url);
  const p = url.pathname;
  if (!p.startsWith('/api/admin/')) return null;

  // Not the app's token? Not ours - hand the request back to the existing website
  // code (admin.html and its own /api/admin routes keep working untouched).
  if (!authed(request, env)) return null;

  const db = env.DB; // must match the binding name in wrangler.toml
  if (!db) return json({ error: 'D1 binding "DB" missing in wrangler.toml' }, 500);

  const m = request.method;

  // GET /api/admin/orders?limit=120&since=...
  if (p === '/api/admin/orders' && m === 'GET') {
    const limit = Math.min(parseInt(url.searchParams.get('limit') || '120', 10), 500);
    const since = url.searchParams.get('since');
    const sql = since
      ? `${orderSelect} WHERE ${COL.updated_at} > ? ORDER BY ${COL.updated_at} DESC LIMIT ?`
      : `${orderSelect} ORDER BY ${COL.id} DESC LIMIT ?`;
    const stmt = since ? db.prepare(sql).bind(since, limit) : db.prepare(sql).bind(limit);
    const { results } = await stmt.all();
    return json({ orders: results || [], server_time: now() });
  }

  // PATCH /api/admin/orders/:id   { status }
  let mm = p.match(/^\/api\/admin\/orders\/(\d+)$/);
  if (mm && m === 'PATCH') {
    const body = await request.json();
    const allowed = ['pending', 'confirmed', 'packed', 'shipped', 'delivered', 'returned', 'cancelled'];
    if (!allowed.includes(body.status)) return json({ error: 'bad status' }, 400);
    await db
      .prepare(`UPDATE ${ORDERS} SET ${COL.status} = ?, ${COL.updated_at} = ? WHERE ${COL.id} = ?`)
      .bind(body.status, now(), mm[1])
      .run();
    return json({ ok: true });
  }

  // POST /api/admin/orders/:id/whatsapp   { template }
  mm = p.match(/^\/api\/admin\/orders\/(\d+)\/whatsapp$/);
  if (mm && m === 'POST') {
    const body = await request.json();
    const t = now();
    await db
      .prepare('INSERT INTO wa_messages (order_id, direction, body, created_at) VALUES (?, ?, ?, ?)')
      .bind(mm[1], 'out', body.template || '', t)
      .run();
    await db
      .prepare(
        `UPDATE ${ORDERS} SET ${COL.wa_status} = 'sent', ${COL.wa_last_sent} = ?, ${COL.updated_at} = ? WHERE ${COL.id} = ?`
      )
      .bind(t, t, mm[1])
      .run();
    return json({ ok: true });
  }

  // GET /api/admin/stats?range=today|7d|30d
  if (p === '/api/admin/stats' && m === 'GET') {
    const range = url.searchParams.get('range') || 'today';
    const days = range === '30d' ? 30 : range === '7d' ? 7 : 1;
    const from = `-${days} days`;

    const agg = await db
      .prepare(
        `SELECT COALESCE(SUM(${COL.total}),0) AS sales,
                COUNT(*) AS orders,
                COALESCE(SUM(${COL.total}),0) AS cod,
                COUNT(*) AS cod_count,
                SUM(CASE WHEN ${COL.status} = 'returned' THEN 1 ELSE 0 END) AS returned
         FROM ${ORDERS} WHERE ${COL.created_at} >= datetime('now', ?)`
      )
      .bind(from)
      .first();

    const daily = await db
      .prepare(
        `SELECT date(${COL.created_at}) AS d, COALESCE(SUM(${COL.total}),0) AS v
         FROM ${ORDERS} WHERE ${COL.created_at} >= datetime('now', ?)
         GROUP BY d ORDER BY d`
      )
      .bind(from)
      .all();

    const rate = agg.orders ? `${Math.round((agg.returned * 100) / agg.orders)}%` : '-';
    return json({
      sales: agg.sales,
      orders: agg.orders,
      cod: agg.cod,
      cod_count: agg.cod_count,
      return_rate: rate,
      series: (daily.results || []).map((r) => r.v),
    });
  }

  // GET /api/admin/products?limit=200
  if (p === '/api/admin/products' && m === 'GET') {
    const limit = Math.min(parseInt(url.searchParams.get('limit') || '200', 10), 500);
    const { results } = await db
      .prepare(
        `SELECT ${PCOL.id} AS id, ${PCOL.name} AS name, ${PCOL.fabric} AS fabric,
                ${PCOL.price} AS price, ${PCOL.sale_price} AS sale_price,
                ${PCOL.stock} AS stock, ${PCOL.active} AS active, ${PCOL.image} AS image_url
         FROM ${PRODUCTS} ORDER BY ${PCOL.id} DESC LIMIT ?`
      )
      .bind(limit)
      .all();
    return json({ products: results || [] });
  }

  // PATCH /api/admin/products/:id   { stock?, price?, sale_price?, active? }
  mm = p.match(/^\/api\/admin\/products\/(\d+)$/);
  if (mm && m === 'PATCH') {
    const body = await request.json();
    const sets = [];
    const vals = [];
    for (const k of ['stock', 'price', 'sale_price', 'active', 'name', 'fabric', 'image']) {
      if (body[k] !== undefined) {
        sets.push(`${PCOL[k]} = ?`);
        vals.push(body[k]);
      }
    }
    if (body.description !== undefined) { sets.push('description = ?'); vals.push(body.description); }
    if (body.image !== undefined) {
      sets.push('images_json = ?');
      vals.push(body.image ? JSON.stringify([body.image]) : null);
    }
    if (!sets.length) return json({ error: 'nothing to update' }, 400);
    vals.push(mm[1]);
    await db.prepare(`UPDATE ${PRODUCTS} SET ${sets.join(', ')} WHERE ${PCOL.id} = ?`).bind(...vals).run();
    return json({ ok: true });
  }

  // POST /api/admin/app/upload   multipart form, field "files"
  // The site's own /api/admin/upload is Basic-auth only, so the app gets its own path
  // into the same R2 bucket. Returns { urls: ["/uploads/..."] }.
  if (p === '/api/admin/app/upload' && m === 'POST') {
    if (!env.IMAGES) return json({ error: 'R2 binding "IMAGES" missing' }, 500);
    const form = await request.formData();
    const files = form.getAll('files');
    const urls = [];
    for (const f of files) {
      if (!f || typeof f === 'string') continue;
      const raw = (f.name || '').split('.').pop().toLowerCase();
      const ext = /^[a-z0-9]{2,5}$/.test(raw) ? raw : 'jpg';
      const key = `uploads/${Date.now()}-${Math.random().toString(36).slice(2, 8)}.${ext}`;
      await env.IMAGES.put(key, await f.arrayBuffer(), {
        httpMetadata: { contentType: f.type || 'image/jpeg' },
      });
      urls.push('/' + key);
    }
    if (!urls.length) return json({ error: 'no files received' }, 400);
    return json({ ok: true, urls });
  }

  // ── discounts ────────────────────────────────────────────────────────────
  if (p === '/api/admin/app/discounts' && m === 'GET') {
    const { results } = await db
      .prepare('SELECT * FROM discounts ORDER BY is_active DESC, id DESC')
      .all();
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
    for (const k of ['code', 'title', 'type', 'value', 'min_cart_value',
                     'usage_limit', 'start_date', 'end_date', 'is_active',
                     'buy_quantity', 'get_quantity', 'get_discount_percent']) {
      if (b[k] !== undefined) { sets.push(k + ' = ?'); vals.push(b[k]); }
    }
    if (!sets.length) return json({ error: 'nothing to update' }, 400);
    vals.push(mm[1]);
    await db.prepare(`UPDATE discounts SET ${sets.join(', ')} WHERE id = ?`).bind(...vals).run();
    return json({ ok: true });
  }

  if (mm && m === 'DELETE') {
    await db.prepare('DELETE FROM discounts WHERE id = ?').bind(mm[1]).run();
    return json({ ok: true });
  }

  // ── banners ──────────────────────────────────────────────────────────────
  if (p === '/api/admin/app/banners' && m === 'GET') {
    const { results } = await db
      .prepare('SELECT * FROM banners ORDER BY sort_order ASC, id ASC')
      .all();
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
      .bind(
        b.eyebrow || null, b.heading, b.subheading || null,
        b.button_text || null, b.button_link || null, b.image_url || null
      )
      .run();
    return json({ ok: true, id: r.meta && r.meta.last_row_id });
  }

  mm = p.match(/^\/api\/admin\/app\/banners\/(\d+)$/);
  if (mm && m === 'PATCH') {
    const b = await request.json();
    const sets = [];
    const vals = [];
    for (const k of ['eyebrow', 'heading', 'subheading', 'button_text',
                     'button_link', 'image_url', 'sort_order', 'is_active']) {
      if (b[k] !== undefined) { sets.push(k + ' = ?'); vals.push(b[k]); }
    }
    if (!sets.length) return json({ error: 'nothing to update' }, 400);
    vals.push(mm[1]);
    await db.prepare(`UPDATE banners SET ${sets.join(', ')} WHERE id = ?`).bind(...vals).run();
    return json({ ok: true });
  }

  if (mm && m === 'DELETE') {
    await db.prepare('DELETE FROM banners WHERE id = ?').bind(mm[1]).run();
    return json({ ok: true });
  }

  // GET /api/admin/app/fabrics - for the product editor's fabric picker
  if (p === '/api/admin/app/fabrics' && m === 'GET') {
    const { results } = await db
      .prepare('SELECT name FROM fabric_categories ORDER BY sort_order ASC')
      .all();
    return json({ fabrics: (results || []).map((r) => r.name) });
  }

  // POST /api/admin/products   { name, fabric, price, sale_price, stock, image, active }
  if (p === '/api/admin/products' && m === 'POST') {
    const b = await request.json();
    if (!b.name) return json({ error: 'name required' }, 400);
    const slug = String(b.name).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
      + '-' + Math.random().toString(36).slice(2, 6);
    const r = await db
      .prepare(
        `INSERT INTO ${PRODUCTS} (${PCOL.name}, slug, ${PCOL.fabric}, description, ${PCOL.price},
             ${PCOL.sale_price}, ${PCOL.stock}, ${PCOL.image}, images_json, ${PCOL.active},
             sort_order, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
             (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM ${PRODUCTS}), datetime('now'))`
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

  // DELETE /api/admin/products/:id
  mm = p.match(/^\/api\/admin\/products\/(\d+)$/);
  if (mm && m === 'DELETE') {
    await db.prepare(`DELETE FROM ${PRODUCTS} WHERE ${PCOL.id} = ?`).bind(mm[1]).run();
    return json({ ok: true });
  }

  // POST /api/admin/courier/postex   { orderId }
  if (p === '/api/admin/courier/postex' && m === 'POST') {
    const { orderId } = await request.json();
    const o = await db.prepare(`${orderSelect} WHERE ${COL.id} = ?`).bind(orderId).first();
    if (!o) return json({ ok: false, reason: 'order not found' }, 404);
    if (!env.POSTEX_TOKEN) return json({ ok: false, reason: 'POSTEX_TOKEN secret not set' }, 500);

    const res = await fetch('https://api.postex.pk/services/integration/api/order/v3/create-order', {
      method: 'POST',
      headers: { token: env.POSTEX_TOKEN, 'content-type': 'application/json' },
      body: JSON.stringify({
        cityName: o.city,
        customerName: o.name,
        customerPhone: o.phone,
        deliveryAddress: o.address,
        invoicePayment: o.total,
        orderDetail: `Order #${o.id}`,
        orderRefNumber: String(o.id),
        orderType: 'Normal',
        transactionNotes: '',
        items: 1,
        pickupAddressCode: env.POSTEX_PICKUP_ADDRESS_CODE || '001',
      }),
    });
    const data = await res.json().catch(() => ({}));
    const cn = data?.dist?.trackingNumber || data?.trackingNumber;
    if (!res.ok || !cn) {
      return json({ ok: false, reason: data?.statusMessage || `PostEx ${res.status}` }, 200);
    }
    const t = now();
    await db
      .prepare(
        `UPDATE ${ORDERS} SET ${COL.courier} = 'PostEx', ${COL.tracking} = ?, ${COL.status} = 'shipped', ${COL.updated_at} = ? WHERE ${COL.id} = ?`
      )
      .bind(cn, t, o.id)
      .run();
    return json({ ok: true, tracking: cn });
  }

  // GET /api/admin/courier/postex/track/:cn
  mm = p.match(/^\/api\/admin\/courier\/postex\/track\/(.+)$/);
  if (mm && m === 'GET') {
    const res = await fetch(
      `https://api.postex.pk/services/integration/api/order/v1/track-order/${mm[1]}`,
      { headers: { token: env.POSTEX_TOKEN || '' } }
    );
    return json(await res.json().catch(() => ({ ok: false })), res.status);
  }

  // A path we do not implement - let the existing website handle it.
  return null;
}
