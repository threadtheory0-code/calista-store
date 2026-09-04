# Round 2 — speed, banner frame, admin tabs, trust badges

## 1. Why it was still slow, and what changed

The first round cached things at the edge. That was correct but it only helps
once the site is on a real domain — and it wasn't the whole problem. The
homepage was making **seven separate calls** before it could show anything:

```
/api/tiktok-pixel-id   /api/products   /api/banners   /api/fabric-categories
/api/settings          /api/nav-tabs   /api/reviews-summary
```

Each one was its own Worker start-up and its own database round trip, one after
another. That is why the site felt slower the more stock was added, and why it
felt slow even on a fast connection.

**Three changes, all live without a custom domain:**

**One request instead of seven.** New `/api/bootstrap` returns products,
banners, fabrics, settings, menu tabs and review totals in a single response,
built from **one** batched database call. Seven round trips became one.

**Repeat visits wait for nothing.** That payload is kept in the visitor's own
browser. A returning shopper — or anyone moving between pages — gets the page
drawn from local storage with no network wait at all, while a fresh copy is
fetched quietly in the background. Saving anything in the admin panel clears
that copy, so you never look at your own site and see the old version.

**The banner stopped downloading ten photos.** Every banner slide used to be
rendered at once, each with its own background image, so a ten-banner homepage
pulled down ten full-size photographs before it was usable. There is now one
image element — the banner on screen. The next one is fetched a moment before
it is needed. On a ten-banner homepage that is roughly a 90% cut in what the
page downloads.

Also: the hover-swap second photo on product cards is no longer downloaded on
phones. It could never be shown there — a phone has no hover — and it was
doubling the number of images the grid pulled down.

**Still the single biggest remaining win:** put the site on a custom domain.
The edge caching is written and correct but does nothing on `*.workers.dev`.
It switches on by itself the moment the site answers on a real domain.

## 2. Banner frame and swipe dots

The banner is now a real image in normal page flow, so the frame is exactly as
tall as the photograph — nothing to measure, no aspect-ratio guesswork, and no
empty band under the picture. The dots sit directly beneath the photo instead
of floating in that gap. The product-page gallery dots were moved the same way.

A banner with no artwork for the current screen gets a plain frame with its
text on it, rather than collapsing to nothing.

## 3. Admin tabs

Eleven tabs no longer run off the right edge. The row wraps onto a second line
when the window is narrow and scrolls sideways on a phone, and it is always
left-aligned so the first tab starts where the content does.

## 4. Trust badges — minimal treatment

Three treatments are laid out side by side in `trust-badges-options.html` (open
it in a browser). **B is what's deployed:** the box is gone and only the
hairline rules between the claims remain, so it stops looking like a widget
competing with the product. The explanation under each claim stays, because
that is what actually settles a cash-on-delivery buyer.

If you prefer **C** — the single quiet row, titles only, no explanations — say
so and it's a one-line change. I'd argue against it: a first-time buyer never
learns that the courier is paid on arrival or that tracking comes by WhatsApp.

## 5. Loopholes found and closed

**Prices came from the browser.** The order endpoint said it recomputed the
total server-side, but it was still multiplying the **price the browser sent**.
The cart lives in local storage, so that price is only ever a suggestion —
anyone could have edited it and paid Rs. 1 for a suit. Every line is now
re-priced from the products table, and the sale price is honoured from the
database, not the page.

**Sold-out items could still be ordered.** An order now stops with a clear
message if an item has gone inactive or out of stock, naming the item and the
quantity actually left.

**Stock never came down.** Placing an order now reduces stock, so "only 2 left"
stays true and two people can't be sold the last piece.

**The bag could disagree with the checkout total.** A bag left open overnight
kept yesterday's prices. Both the cart and the checkout now re-price against
the catalogue before anything is shown, and say plainly what moved — *"Zara —
3PC Lawn is now Rs. 4,299"*, *"Only 1 left — quantity reduced"*, *"X has sold
out"*. Nothing changes silently, and the total shown is the total the order is
booked at.

**Add to Cart on a sold-out product page** is now disabled and reads Sold Out.

**The owner's own browser went stale.** Any save in the admin panel now clears
the stored catalogue copy, and placing an order does too.

## Deploy

Replace the nine files, deploy the Worker, load the site once. No database work
needed — the reviews table and the new order columns apply themselves.
