# Site audit — 14 Oct 2026

## Fixed in this round

1. **Products showed only 2 photos, and no description.** The product page looked
   itself up in the trimmed grid list (built for speed last round), which carries
   only the two grid photos. New `/api/product?slug=` returns the full row —
   every gallery photo and the description — edge-cached, one small request.
2. **Customers could keep an old `app.js` for up to a day after a deploy** (it is
   cached 24h). New pages + old script = broken product page. Every page now loads
   `app.js?v=20261014` / `styles.css?v=20261014`, so a deploy reaches everyone at once.
   Bump the number whenever either file changes.
3. **A bad connection could empty the customer's bag.** If the catalogue failed to
   load, the bag check treated every item as "no longer available" and removed it.
4. **A failed catalogue load showed nine made-up sample products** that could be
   added to the bag and then failed at checkout. Sample data is now local-preview only.
5. **Search/social previews carried raw HTML** in the description, and a relative
   image path Facebook/WhatsApp can't fetch. Both cleaned.
6. **Gallery order** — main photo always first, duplicates removed.

## New: Frequently Bought Together

Product page, directly under Add to Cart. Up to three in-stock pieces in the same
colour, with checkboxes, a live total and one "Add all to cart" button.

How a colour is found, in order: Admin **Colour** field → a colour word in the
name → a "Colour: …" line in the description → a colour word early in the
description. Exact shade ranks first (Maroon ↔ Maroon), then the family
(Maroon ↔ Wine ↔ Burgundy). Matching accessories (bag, clutch, shawl, dupatta…)
get priority, then a different fabric. Ladies only see Ladies, Gents only Gents.
Black / white / grey / beige accessories pair with anything.

Admin: Colour field on the product form, a `color` CSV column, **Set colour** in
the bulk bar, **Check colours** report, and an on/off + title in Trust settings.

## Speed — current state

- Grid photos: own bucket, 1-year cache, 600px copies. Done.
- Database: indexes, no per-request migrations, trimmed grid payload. Done.
- Product page: was downloading the whole catalogue to show one product (only
  when arriving direct from a link / ad); now one small request.
- Remaining: 452 gallery photos still on GitHub/Shopify — affects product pages
  only. Step 1 with the gallery box ticked, then Step 2.

## Not changed — worth knowing

- Edge cache is cleared per Cloudflare city. An admin edit can take up to ~2 min
  to show in other cities. Normal; no action.
- Social link previews are filled in by JavaScript, which WhatsApp/Facebook don't
  run — shared links show the generic store title. Fixing needs server-rendered
  product pages; a larger job.
