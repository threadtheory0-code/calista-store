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
// tabIds: hand-picked assignments to include. fabrics: fabric names to match.
// allIfEmpty: when neither is configured, return the whole catalogue.
async function productsInScope(env, tabIds, fabrics, allIfEmpty) {
  const order = 'ORDER BY p.sort_order ASC, p.id DESC';
  if (!tabIds.length && !fabrics.length) {
    if (!allIfEmpty) return [];
    const { results } = await env.DB
      .prepare(`SELECT p.* FROM products p WHERE p.is_active = 1 ${order}`).all();
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
    `SELECT p.* FROM products p WHERE p.is_active = 1 AND (${conds.join(' OR ')}) ${order}`
  ).bind(...binds).all();
  return results;
}

let SCHEMA_READY = false;
async function ensureSchema(env) {
  if (SCHEMA_READY) return;
  const migrations = [
    "ALTER TABLE nav_tabs ADD COLUMN fabrics TEXT",
    "ALTER TABLE nav_tabs ADD COLUMN show_in_topbar INTEGER NOT NULL DEFAULT 1",
    "ALTER TABLE orders ADD COLUMN postex_tracking TEXT",
    "ALTER TABLE orders ADD COLUMN postex_status TEXT",
    "ALTER TABLE orders ADD COLUMN postex_booked_at TEXT"
  ];
  for (const sql of migrations) {
    try { await env.DB.prepare(sql).run(); } catch (e) { /* already there */ }
  }
  SCHEMA_READY = true;
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

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method;

    const isAdminRoute = path === '/admin.html' || path.startsWith('/api/admin/');
    if (isAdminRoute && !checkAdminAuth(request, env)) {
      return unauthorized();
    }

    if (path.startsWith('/uploads/') && method === 'GET') {
      try {
        const key = path.slice(1);
        const obj = await env.IMAGES.get(key);
        if (!obj) return new Response('Not found', { status: 404 });
        return new Response(obj.body, {
          headers: {
            'Content-Type': obj.httpMetadata?.contentType || 'application/octet-stream',
            'Cache-Control': 'public, max-age=31536000, immutable'
          }
        });
      } catch (err) {
        return new Response('Error', { status: 500 });
      }
    }

    if (path === '/api/admin/upload' && method === 'POST') {
      try {
        const formData = await request.formData();
        const files = formData.getAll('files');
        const urls = [];
        for (const file of files) {
          if (!file || typeof file === 'string') continue;
          const rawExt = (file.name || '').split('.').pop().toLowerCase();
          const ext = /^[a-z0-9]{2,5}$/.test(rawExt) ? rawExt : 'jpg';
          const key = `uploads/${Date.now()}-${Math.random().toString(36).slice(2, 8)}.${ext}`;
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
        "ALTER TABLE orders ADD COLUMN postex_booked_at TEXT"
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
        const { results } = await env.DB
          .prepare('SELECT * FROM orders ORDER BY created_at DESC')
          .all();
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
        if (!b.heading) return json({ error: 'Heading is required' }, 400);
        await env.DB.prepare(
          `INSERT INTO banners (eyebrow, heading, subheading, button_text, button_link, image_url, image_url_mobile, mobile_fabric_source, sort_order)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
        ).bind(
          b.eyebrow || null, b.heading, b.subheading || null,
          b.button_text || null, b.button_link || null, b.image_url || null, b.image_url_mobile || null,
          b.mobile_fabric_source || null, Number(b.sort_order) || 0
        ).run();
        return json({ success: true });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/banners' && method === 'PATCH') {
      try {
        const b = await request.json();
        if (!b.id || !b.heading) return json({ error: 'Missing id or heading' }, 400);
        await env.DB.prepare(
          `UPDATE banners SET eyebrow=?, heading=?, subheading=?, button_text=?, button_link=?, image_url=?, image_url_mobile=?, mobile_fabric_source=?, sort_order=?, is_active=?
           WHERE id=?`
        ).bind(
          b.eyebrow || null, b.heading, b.subheading || null,
          b.button_text || null, b.button_link || null, b.image_url || null, b.image_url_mobile || null,
          b.mobile_fabric_source || null, Number(b.sort_order) || 0,
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

    if (path === '/api/products' && method === 'GET') {
      try {
        await ensureSchema(env);

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
            .prepare('SELECT * FROM products WHERE is_active = 1 AND LOWER(fabric) = LOWER(?) ORDER BY sort_order ASC, id DESC')
            .bind(fabric)
            .all();
          return json(results);
        }
        const { results } = await env.DB
          .prepare('SELECT * FROM products WHERE is_active = 1 ORDER BY sort_order ASC, id DESC')
          .all();
        return json(results);
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    /* ---------------- Nav tabs (hamburger menu: Men / Women categories) ---------------- */
    if (path === '/api/nav-tabs' && method === 'GET') {
      try {
        await ensureSchema(env);
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
        const body = await request.json();
        const { customer_name, phone, address, city, items, discount_code } = body;

        if (!customer_name || !phone || !address || !city || !items || !items.length) {
          return json({ error: 'Missing required fields' }, 400);
        }

        // Always recompute the total server-side — never trust a client-sent total
        let subtotal = items.reduce((sum, i) => sum + i.price * i.qty, 0);
        let discountAmount = 0;
        let appliedCode = null;

        if (discount_code) {
          const discount = await env.DB.prepare('SELECT * FROM discounts WHERE code = ? AND is_active = 1').bind(discount_code.trim().toUpperCase()).first();
          if (discount) {
            const result = calculateDiscount(discount, items);
            if (!result.error) {
              discountAmount = result.amount;
              appliedCode = discount.code;
              ctx.waitUntil(env.DB.prepare('UPDATE discounts SET used_count = used_count + 1 WHERE id = ?').bind(discount.id).run());
            }
          }
        }

        const total = Math.max(0, subtotal - discountAmount);

        await env.DB.prepare(
          `INSERT INTO orders (customer_name, phone, address, city, items_json, total, discount_code, discount_amount, status)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending')`
        ).bind(customer_name, phone, address, city, JSON.stringify(items), total, appliedCode, discountAmount).run();

        ctx.waitUntil(notifyNewOrder({ customer_name, phone, address, city, items, total }, env));

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
