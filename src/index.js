import { handleAdmin } from './api-admin.js';
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
        await env.DB.prepare(
          'INSERT INTO fabric_categories (name, sort_order) VALUES (?, (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM fabric_categories))'
        ).bind(name.trim()).run();
        return json({ success: true });
      } catch (err) {
        if (String(err.message).includes('UNIQUE')) return json({ error: 'That fabric already exists' }, 400);
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
          `INSERT INTO banners (eyebrow, heading, subheading, button_text, button_link, image_url, sort_order)
           VALUES (?, ?, ?, ?, ?, ?, ?)`
        ).bind(
          b.eyebrow || null, b.heading, b.subheading || null,
          b.button_text || null, b.button_link || null, b.image_url || null, Number(b.sort_order) || 0
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
          `UPDATE banners SET eyebrow=?, heading=?, subheading=?, button_text=?, button_link=?, image_url=?, sort_order=?, is_active=?
           WHERE id=?`
        ).bind(
          b.eyebrow || null, b.heading, b.subheading || null,
          b.button_text || null, b.button_link || null, b.image_url || null, Number(b.sort_order) || 0,
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
        results.forEach(r => { settings[r.key] = r.value; });
        return json(settings);
      } catch (err) {
        return json({ error: err.message }, 500);
      }
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
        const { results } = await env.DB
          .prepare('SELECT * FROM products WHERE is_active = 1 ORDER BY sort_order ASC, id DESC')
          .all();
        return json(results);
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
