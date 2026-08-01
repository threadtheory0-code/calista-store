export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    if (url.pathname === '/api/products' && request.method === 'GET') {
      try {
        const { results } = await env.DB
          .prepare('SELECT * FROM products WHERE is_active = 1 ORDER BY created_at DESC')
          .all();
        return new Response(JSON.stringify(results), {
          headers: { 'Content-Type': 'application/json' }
        });
      } catch (err) {
        return new Response(JSON.stringify({ error: err.message }), {
          status: 500,
          headers: { 'Content-Type': 'application/json' }
        });
      }
    }

    if (url.pathname === '/api/order' && request.method === 'POST') {
      try {
        const body = await request.json();
        const { customer_name, phone, address, city, items, total } = body;

        if (!customer_name || !phone || !address || !city || !items || !total) {
          return new Response(JSON.stringify({ error: 'Missing required fields' }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' }
          });
        }

        await env.DB.prepare(
          `INSERT INTO orders (customer_name, phone, address, city, items_json, total, status)
           VALUES (?, ?, ?, ?, ?, ?, 'pending')`
        ).bind(customer_name, phone, address, city, JSON.stringify(items), total).run();

        return new Response(JSON.stringify({ success: true }), {
          headers: { 'Content-Type': 'application/json' }
        });
      } catch (err) {
        return new Response(JSON.stringify({ error: err.message }), {
          status: 500,
          headers: { 'Content-Type': 'application/json' }
        });
      }
    }

    return env.ASSETS.fetch(request);
  }
};
