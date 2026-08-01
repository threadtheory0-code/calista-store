// GET /api/products
// Returns all active products from the D1 database.
export async function onRequestGet(context) {
  const { env } = context;

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
