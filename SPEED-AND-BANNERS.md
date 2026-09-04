# Speed fixes + per-device banners

## Part 1 — Why the site got slow, and what changed

### The real cause: every photo was a database trip

Your photos live in R2 (Cloudflare's file storage) and are only reachable
through the Worker. Cloudflare **does not cache Worker responses by itself**.
So every single photo on every single page view was:

`visitor → Worker starts up → reads the file from R2 → streams it back`

With 4 women's products that was tolerable. Once the gents catalogue went up,
a collection page asked for 30+ photos and each one paid that full round trip —
which is exactly when you noticed it. The `Cache-Control: immutable` header we
had set only helped a visitor's **second** visit; the first visit, and every new
visitor, paid full price.

**Fixed.** The Worker now:

1. **Puts the CDN in front of photos.** Each photo is stored in Cloudflare's
   edge cache the first time anyone asks for it. Every later visitor is served
   from the edge — no Worker, no R2.
2. **Answers a returning browser with "not modified".** Photos now carry an
   ETag, so a repeat visitor gets a tiny 304 instead of the whole file.
3. **Caches the read-only API** (`/api/products`, `/api/banners`,
   `/api/settings`, `/api/fabric-categories`, `/api/nav-tabs`) at the edge for
   ~2 minutes, with stale-while-revalidate. Saving anything in the admin panel
   clears that cache immediately, so your edits still appear straight away.

### ⚠ One thing you must do: put the site on a custom domain

This is the single biggest remaining win, and it is not something I can do from

So while `calista-store.threadtheory0.workers.dev` is your live URL, points 1
and 3 above are **silently doing nothing**. The code is already written and
correct — it switches on by itself the moment the site answers on a real domain.

**How to switch it on:**

1. Cloudflare dashboard → **Workers & Pages** → `calista-store`
2. **Settings → Domains & Routes → Add → Custom Domain**
3. Enter your domain (e.g. `calistastore.pk` and `www.calistastore.pk`)
4. If the domain isn't on Cloudflare yet, add it first (Websites → Add a site)
   and point your registrar at the nameservers Cloudflare gives you.

Expect roughly a 3–5× improvement in repeat page loads from that step alone,
plus it's better for SEO, trust and TikTok/Meta ad approval than a
`workers.dev` URL.

### Grid photos are now a quarter of the weight

A product card is about 180px wide on a phone, but the grid was downloading the
full 1400px master for every card. Now:

- every new product upload also writes a **600px copy** beside the master
  (`…-abc.thumb.webp`);
- product grids, the cart, the cart drawer, search results, "You May Also
  Like", the fabric strip and the Follow gallery all ask for that small copy;
- the product page itself still shows the full-quality master;
- if a small copy doesn't exist, the Worker serves the master instead — so
  nothing can break.

**One-time action for photos already uploaded:** Admin → **Products** tab →
*"Build small copies for existing photos"*. Press it and leave the tab open
until it says Done. It skips anything already handled, so it's safe to re-run
whenever you've bulk-imported photos. Roughly 200 KB per card becomes ~40 KB.

### Smaller fixes

- **Fonts no longer block the first paint.** The Google Fonts stylesheet was
  render-blocking on every page; it now loads alongside the page instead of in
  front of it.
- **Image-only banners can now be saved.** The API was rejecting a banner with
  a blank heading even though the storefront supports artwork-only banners.
- **Admin lists use the small copies too**, so the Products tab with a long
  catalogue stops crawling.

### If you upload photos through GitHub instead of the admin panel

Photos added straight to the repo (or pasted in as external URLs) skip the
admin panel's compressor entirely — that's how multi-megabyte originals end up
on the site. Two rules:

1. **Upload product photos through Admin → Products.** It resizes to 1400px,
   converts to WebP, targets ~200 KB, and builds the small grid copy. It tells
   you the saving after each upload.
2. If you must add them via GitHub, resize to **1400px on the long edge** and
   save as WebP before committing.

---

## Part 2 — Different banners for desktop and mobile

There are now **two mechanisms**, and you can use either or both.

### A. One banner, two images (best for the same message)

Use this when the banner says the same thing on both devices and you just need
a wide crop and a tall crop.

Admin → **Banners**:

- **Banner Image — Desktop**: upload the wide artwork (~20:9 works best)
- **Banner Image — Mobile**: upload the tall crop (~4:5 fills a phone)
- **Show This Banner To**: leave on **Everyone**

A phone downloads only the mobile image; a laptop downloads only the desktop
one. If you upload just one, both devices use it.

### B. Two separate banners, one per device (best for different messages)

New. Use this when desktop and mobile should show genuinely different
banners — different artwork, heading, button and link.

1. Admin → **Banners** → create your banner as usual
2. Set **Show This Banner To** → **Desktop / laptop visitors only**
3. Save
4. Create a second banner with the phone artwork and copy
5. Set **Show This Banner To** → **Mobile visitors only**
6. Save

The homepage picks the set that matches the visitor's screen (the cut-off is
720px wide — phones and small tablets count as mobile). The Banners list has a
**Shown to** column so you can see at a glance which is which. If you tick
*mobile only* on every banner, desktop visitors fall back to showing them
rather than seeing an empty space.

**Activation:** nothing to switch on. The `device` column is added
automatically on the first request after you deploy (there's also
`schema-banner-device.sql` if you'd rather run it by hand in the D1 console).
Existing banners default to **Everyone**, so nothing changes until you say so.

### C. Auto-rotating mobile banner (already there)

Admin → Banners → **Or — Auto-Rotate Mobile Image From a Fabric**: pick a
fabric and the mobile banner shows a random real product photo from that
fabric on every page load. Overrides the fixed mobile image.

---

## Deploy order

1. Push `src/index.js`, `public/*` and `schema-banner-device.sql`
2. Deploy the Worker
3. Load the site once (this applies the `device` column)
4. Admin → Products → **Build small copies for existing photos**
5. Attach a custom domain — the biggest single win
