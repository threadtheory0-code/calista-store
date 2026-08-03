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
  const itemsText = order.items.map(i => `${i.name} (${i.size}) x${i.qty}`).join(', ');
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
          .prepare('SELECT * FROM products ORDER BY created_at DESC')
          .all();
        return json(results);
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/admin/products' && method === 'POST') {
      try {
        const p = await request.json();
        if (!p.name || !p.slug || !p.price || !p.sizes || !p.image_url) {
          return json({ error: 'Missing required fields' }, 400);
        }
        await env.DB.prepare(
          `INSERT INTO products (name, slug, fabric, description, price, sale_price, sizes, image_url, image_url_2, stock)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
        ).bind(
          p.name, p.slug, p.fabric || null, p.description || null,
          p.price, p.sale_price || null, p.sizes, p.image_url, p.image_url_2 || null, p.stock || 0
        ).run();
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

    if (path === '/api/products' && method === 'GET') {
      try {
        const { results } = await env.DB
          .prepare('SELECT * FROM products WHERE is_active = 1 ORDER BY created_at DESC')
          .all();
        return json(results);
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    if (path === '/api/order' && method === 'POST') {
      try {
        const body = await request.json();
        const { customer_name, phone, address, city, items, total } = body;

        if (!customer_name || !phone || !address || !city || !items || !total) {
          return json({ error: 'Missing required fields' }, 400);
        }

        await env.DB.prepare(
          `INSERT INTO orders (customer_name, phone, address, city, items_json, total, status)
           VALUES (?, ?, ?, ?, ?, ?, 'pending')`
        ).bind(customer_name, phone, address, city, JSON.stringify(items), total).run();

        ctx.waitUntil(notifyNewOrder({ customer_name, phone, address, city, items, total }, env));

        return json({ success: true });
      } catch (err) {
        return json({ error: err.message }, 500);
      }
    }

    return env.ASSETS.fetch(request);
  }
};
