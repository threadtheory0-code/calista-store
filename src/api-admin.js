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
};

const ORDERS = 'orders';
const PRODUCTS = 'products';

// ── helpers ────────────────────────────────────────────────────────────────

const json = (data, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8' },
  });

const authed = (request, env) => {
  const h = request.headers.get('authorization') || '';
  const token = h.startsWith('Bearer ') ? h.slice(7) : '';
  return env.ADMIN_TOKEN && token === env.ADMIN_TOKEN;
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
                ${PCOL.stock} AS stock, ${PCOL.active} AS active
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
    for (const k of ['stock', 'price', 'sale_price', 'active']) {
      if (body[k] !== undefined) {
        sets.push(`${PCOL[k]} = ?`);
        vals.push(body[k]);
      }
    }
    if (!sets.length) return json({ error: 'nothing to update' }, 400);
    vals.push(mm[1]);
    await db.prepare(`UPDATE ${PRODUCTS} SET ${sets.join(', ')} WHERE ${PCOL.id} = ?`).bind(...vals).run();
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
