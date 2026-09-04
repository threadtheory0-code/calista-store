# Where each file goes in your repo

Upload these into your `calista-store` repository, keeping the same folder
structure. Every file here is a **replacement** for the one already in the repo,
except the two `.sql` files and the two `.md` files, which are new.

```
repo root
├── src/
│   └── index.js                    ← REPLACE
├── public/
│   ├── index.html                  ← REPLACE
│   ├── collection.html             ← REPLACE
│   ├── product.html                ← REPLACE
│   ├── cart.html                   ← REPLACE
│   ├── checkout.html               ← REPLACE
│   ├── admin.html                  ← REPLACE
│   ├── app.js                      ← REPLACE
│   └── styles.css                  ← REPLACE
├── schema-reviews.sql              ← NEW (reference only)
├── schema-banner-device.sql        ← NEW (reference only)
├── CONVERSION-FEATURES.md          ← NEW (read this)
└── SPEED-AND-BANNERS.md            ← NEW (read this)
```

Do **not** upload anything else. Your `wrangler.toml`, `functions/`,
`public/images/` and the other `schema-*.sql` files are unchanged.

## Steps

1. Upload the files above (GitHub web: *Add file → Upload files*, drag the
   `src` and `public` folders in, commit).
2. Deploy the Worker (Cloudflare dashboard → Workers & Pages →
   `calista-store` → *Deploy*, or it deploys itself if the repo is connected).
3. **Load the live site once.** This creates the `reviews` table and the two new
   order columns (`verified_at`, `risk_note`) automatically. If you'd rather do
   it by hand, paste `schema-reviews.sql` into the D1 console instead.
4. Admin → **Products** → *Build small copies for existing photos*. One-time,
   for photos uploaded before the speed work. Leave the tab open until it says
   Done.
5. Admin → **Site** → *Trust & conversion*: write your checkout note, and the
   exchange/returns line if you have a policy. Leave a badge out rather than
   promise something you'd argue about later.
6. Admin → **Reviews**: add your first reviews. Nothing goes live until you tick
   the consent box.

## The one thing still worth doing

Put the site on a custom domain. The edge caching in `src/index.js` is written
and correct but does nothing on `*.workers.dev` — it switches on by itself the
moment the site answers on a real domain. Roughly 3–5× faster repeat loads, plus
better SEO and ad approval. Instructions are in `SPEED-AND-BANNERS.md`.

## What's in this batch

**Speed** — edge caching for photos and read-only API, 600px grid copies,
non-blocking fonts, image-only banners.

**Per-device banners** — one banner with separate desktop/mobile artwork, or
two separate banners via *Show This Banner To*.

**Conversion** — phone/address validation and duplicate guard on orders,
per-number order history and a WhatsApp confirm button in the Orders table,
consent-gated reviews with ratings on cards and product pages, truthful
low-stock and sold-out notes, editable trust badges, and a browser-only
"still in your bag" reminder. Full detail and the decisions I made on your
behalf are in `CONVERSION-FEATURES.md`.
