# Conversion layer — what was built, and the calls I made for you

You asked me to go ahead without waiting on the questions doc, so I built the
conversion features on the **conservative** reading of each open question. Every
decision below is reversible from Admin → Site → *Trust & conversion*, and
nothing on the storefront makes a claim you haven't typed in yourself.

The one rule I held to: **no invented urgency and no invented social proof.**
No countdown timers, no "12 people are viewing this", no "only 2 left" unless
the stock column actually says 2. Those tactics convert once and cost you the
repeat customer — and on a cash-on-delivery store, the repeat customer is the
whole business.

---

## 1. Cash-on-delivery fraud — reduced at the point of entry

**The call I made:** verify by WhatsApp confirmation, not by taking money up
front. Asking for an advance payment on a new store loses more real orders than
it saves in fake ones.

What now happens:

- **Phone numbers are validated and normalised** before an order is accepted.
  Anything that isn't a real Pakistani mobile (`03XXXXXXXXX`, with `+92`,
  `0092` and `92` all accepted and cleaned up) is refused at checkout with a
  plain-language message. Junk numbers were the cheapest fraud route and it is
  now closed.
- **Addresses under 10 characters are refused** — "Lahore" alone is not a
  delivery address.
- **Double-tap protection.** The same number placing an identical order within
  10 minutes gets the first order back instead of a second booking.
- **Order history per number.** The Orders table has a new **History** column:
  how many orders that number has placed, how many were delivered, and how many
  were cancelled — in gold if there are cancellations. A number that has
  refused two deliveries is visible before you book the third.
- **Confirm on WhatsApp button** next to every order's phone number. It opens
  WhatsApp with the order number, total and a "reply YES and we'll dispatch"
  message already written. Setting an order to *confirmed* now also stamps the
  time it was verified.

**Still yours to decide:** whether high-value orders need an advance. If you
want that, tell me the rupee threshold and I'll add it — the plumbing is in
place.

## 2. Discounts and urgency — truthful only

**The call I made:** the site may only state facts it can prove from the
database.

- "Only N left in stock" appears only when the product's real stock is at or
  below your threshold (Admin → Site, default **3**, set 0 to switch it off).
- Sold-out products get a **Sold Out** badge on the card and an honest note on
  the product page instead of a dead Add-to-Cart.
- No flash-sale countdowns and no fake original prices. Your existing
  Discounts tab already does real, dated promotions — that is the honest
  version of urgency and it is enough.

**If you want to run genuine flash sales** (a real end time you will honour), say
so and I'll add a countdown that reads from the discount's actual end date.

## 3. Abandoned carts — done without an email list

**The call I made:** no abandoned-cart emails. Emailing people who never
consented is both a legal problem and a spam-folder problem, and you don't have
a consented list yet.

Instead, a shopper returning to the site with items in their bag sees one quiet
bar: *"2 items are still in your bag — Rs. 9,198 — Resume"*. It lives entirely
in their own browser, shows once per session, and can be dismissed. No email
address, no consent, no cost.

**When you do want email:** the Subscribers tab already collects addresses with
consent. Once that list has real signups, an abandoned-cart email to *those*
people is legitimate and I can build it.

## 4. Reviews — consent-gated by design

There is a new **Reviews** tab in the admin panel.

- You type in what a customer actually sent you: display name, city, rating,
  and their words.
- A review **cannot go live** until you tick *"Customer agreed to have this
  published"*. The tick is enforced in the panel and again on the server — a
  review without consent stays hidden no matter what.
- Use a first name or first name + initial, not a full name.
- Approved reviews show as a rating row on product cards and the product page,
  plus a review list under the product, footed with *"Shared with the
  customer's permission."*
- Products with no approved reviews show **nothing** — no empty five-star row,
  no "0 reviews". A blank rating reads as a bad rating.

Getting the consent is a one-line WhatsApp message: *"Thank you! May we share
your feedback on our website with your first name?"* Keep the screenshot.

## 5. Trust badges — your claims, in your words

Admin → Site → *Trust & conversion* → **Trust badges**, one per line as
`Title | short explanation`. They appear on every product page.

The three I've set as defaults are ones the site can already back:

```
Cash on Delivery|Pay the courier when your parcel arrives
Tracked Delivery|Booked with PostEx — tracking sent on WhatsApp
WhatsApp Support|Message us any time before or after ordering
```

I deliberately did **not** add authenticity guarantees, "100% original fabric",
money-back promises or an exchange window — those are promises only you can
make. Two fields are waiting for you:

- **What happens after an order is placed** (shown at checkout). Default:
  *"We'll confirm your order on WhatsApp or by phone before dispatch."*
- **Exchange / returns line** — empty until you decide the policy. Write it
  as a sentence you would honour: *"Exchange within 7 days if the parcel is
  unopened."*

Leave a badge out rather than promise something you'd argue about later.

---

## Deploy

1. Push `src/index.js`, `public/*`, `schema-reviews.sql`
2. Deploy the Worker
3. Load the site once — the `reviews` table and the two new order columns are
   created automatically (or run `schema-reviews.sql` in the D1 console)
4. Admin → **Site** → *Trust & conversion*: write your checkout note and, if
   you have a policy, the exchange line
5. Admin → **Reviews**: add your first consented reviews

## What I did not build, and why

| Asked about | Not built | Why |
| --- | --- | --- |
| Advance payment for COD | No | Costs more real orders than it saves; needs your risk call and a rupee threshold |
| Abandoned-cart email | No | No consented email list yet |
| Flash-sale countdown | No | Only worth it against a real end date you'll honour |
| Authenticity / guarantee badges | No | Only you can make that promise |
| Review requests sent automatically | No | Needs a consent line in the order flow first — tell me and I'll add it |

Any of these becomes a short job once you've made the call.
