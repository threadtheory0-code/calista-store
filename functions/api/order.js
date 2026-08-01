// POST /api/order
// Body: { customer_name, phone, address, city, items, total }
// Saves the order to D1. Items are stored as JSON since D1/SQLite
// has no native array/object column type.
export async function onRequestPost(context) {
  const { env, request } = context;

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

    // Optional next step: trigger a WhatsApp/email notification here
    // via a webhook (e.g. CallMeBot, Twilio, or a Zapier/Make webhook).

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
