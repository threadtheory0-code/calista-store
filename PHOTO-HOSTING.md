# Why browsing was slow — and what was changed

## The finding

The Gents and Ladies halves of the catalogue were hosted in two different
places, and the code had no idea. Measured directly:

| | Gents | Ladies |
|---|---|---|
| Host | `raw.githubusercontent.com` | `cdn.shopify.com` |
| Size per photo | 143 KB | 138 KB |
| Time to fetch | ~830 ms | fast (real CDN) |
| **Browser told to cache for** | **5 minutes** | **1 year** |
| Can be resized on request | no | yes |

That cache line is the whole story. GitHub's raw file service sends
`Cache-Control: max-age=300`. A browser therefore throws every Gents photo away
after five minutes and downloads it again — on the next visit, on the next page,
on the way back from a product page. A 30-card Gents grid re-fetched roughly
4.3 MB, repeatedly, from a service that is designed for reading source code and
caps how many files it will hand over at once.

The Ladies photos never had this problem, which is why only Gents felt broken.
Identical code, different hosting.

Two smaller findings alongside it:

- Shopify's CDN resizes on request. `&width=600` returned the same photo at
  **87 KB instead of 138 KB** — 37% off, no re-upload.
- Hotlinking GitHub raw for a commercial storefront is against their terms.
  They can throttle or block it, which would leave Gents with no photos at all.

## What was changed

**1. Gents photos now come from a real content network.**
The same GitHub repository is also served by jsDelivr, a global CDN. Photo URLs
are rewritten on the way out — same file, same folder, no re-upload — and the
cache instruction goes from 5 minutes to 7 days. This works the moment the code
deploys, with nothing to click.

**2. An admin tool moves the photos into your own store.**
The permanent fix. Admin → Products → *Move outside photos into my store*
fetches each photo, stores it in your own R2 bucket, and repoints the
catalogue. From then on they carry a one-year immutable cache, sit behind your
own edge caching, and can have 600px grid copies built. It also ends the
dependency on GitHub and Shopify staying up. Runs in small batches, safe to
re-run, picks up where it left off.

**3. Ladies photos are requested at the size they are displayed.**
Grids ask for 600px, product galleries 1200px. 37% less data per card.

**4. Nine wasted database calls removed from every page load.**
A setup routine ran before every product query, firing nine
"add this column" statements of which eight always failed — the columns were
added weeks ago — each one a full round trip, in sequence, before the real
query started. Cloudflare recycles idle servers constantly, so a large share of
visitors paid for all nine. It is now one cheap version check, and storefront
pages never wait for it at all.

**5. The database had no indexes. It has nine now.**
Every category page was reading every row of `products`, re-reading the whole
tab-assignment table for each one, then sorting in memory — getting linearly
slower with every product added, which is exactly what you were seeing as Gents
stock went up. Two of the new indexes are expression indexes on
`LOWER(TRIM(fabric))`, because that is how the queries compare fabric names and
an ordinary index cannot serve it.

**6. Grids stopped downloading data they never show.**
Product lists were sending every product's full description — around 2 KB of
pasted HTML each — plus its entire gallery list, to render cards that show a
photo, a name and a price. A large category page was over a megabyte of JSON.
The card query now selects eleven columns. The product page still reads the
full row for the one product it displays.

One consequence worth knowing: site search now matches product names and
fabrics, not description text. That was the only thing using descriptions in
the list.

**7. Connection hints** for both photo hosts, so the handshake happens in
parallel with the page instead of after it.

## Still worth doing

Put the store on a custom domain. The edge caching is written and correct but
does nothing on `*.workers.dev`. It switches on by itself the moment the site
answers on a real domain.
