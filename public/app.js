/* ============================================================
   Store logic: fetch products from the API (falls back to
   sample data if the API isn't wired up yet), cart stored in
   localStorage, cart badge kept in sync across pages.
   ============================================================ */

const SAMPLE_PRODUCTS = [
  { id: 1, name: "Zara — 3PC Lawn Embroidered", slug: "zara-3pc-lawn-embroidered",
    fabric: "Lawn", description: "Three-piece embroidered lawn suit with digital printed dupatta.",
    price: 6999, sale_price: 4599,
    image_url: "/images/placeholder-1.svg", image_url_2: "/images/placeholder-1b.svg", stock: 25 },
  { id: 2, name: "Meherbano — 3PC Chiffon", slug: "meherbano-3pc-chiffon",
    fabric: "Chiffon", description: "Party-wear chiffon suit with hand embellished neckline.",
    price: 8999, sale_price: 6299,
    image_url: "/images/placeholder-2.svg", image_url_2: "/images/placeholder-2b.svg", stock: 15 },
  { id: 3, name: "Anaya — 2PC Khaddar", slug: "anaya-2pc-khaddar",
    fabric: "Khaddar", description: "Winter khaddar shirt and trouser with block print.",
    price: 4999, sale_price: null,
    image_url: "/images/placeholder-3.svg", image_url_2: "/images/placeholder-3b.svg", stock: 30 },
  { id: 4, name: "Rania — 3PC Lawn Printed", slug: "rania-3pc-lawn-printed",
    fabric: "Lawn", description: "Digital printed lawn shirt, trouser and dupatta.",
    price: 5499, sale_price: null,
    image_url: "/images/placeholder-4.svg", image_url_2: "/images/placeholder-4b.svg", stock: 20 },
  { id: 5, name: "Hania — 3PC Lawn Embroidered Luxury", slug: "hania-3pc-lawn-luxury",
    fabric: "Lawn", description: "Luxury embroidered lawn with organza dupatta.",
    price: 9499, sale_price: 7999,
    image_url: "/images/placeholder-5.svg", image_url_2: "/images/placeholder-5b.svg", stock: 12 },
  { id: 6, name: "Sana — 2PC Lawn Printed", slug: "sana-2pc-lawn-printed",
    fabric: "Lawn", description: "Everyday printed lawn kurta and trouser.",
    price: 3999, sale_price: 2999,
    image_url: "/images/placeholder-6.svg", image_url_2: "/images/placeholder-6b.svg", stock: 40 },
  { id: 7, name: "Noreen — 3PC Chiffon Embellished", slug: "noreen-3pc-chiffon-embellished",
    fabric: "Chiffon", description: "Formal embellished chiffon with hand-worked neckline.",
    price: 10999, sale_price: 8499,
    image_url: "/images/placeholder-7.svg", image_url_2: "/images/placeholder-7b.svg", stock: 8 },
  { id: 8, name: "Iqra — 2PC Khaddar Printed", slug: "iqra-2pc-khaddar-printed",
    fabric: "Khaddar", description: "Warm printed khaddar for winter daywear.",
    price: 4499, sale_price: null,
    image_url: "/images/placeholder-8.svg", image_url_2: "/images/placeholder-8b.svg", stock: 22 },
  { id: 9, name: "Mahira — 3PC Silk", slug: "mahira-3pc-silk",
    fabric: "Silk", description: "Festive pure silk suit with heavy embellished dupatta.",
    price: 12999, sale_price: 9999,
    image_url: "/images/placeholder-9.svg", image_url_2: "/images/placeholder-9b.svg", stock: 6 },
];

/* ---------------- Cached data layer ----------------
   Every page used to re-fetch /api/products and /api/settings two or
   three times (grid, gallery, fabric tiles, search). Now each endpoint
   is fetched once per page and kept in sessionStorage for 90 seconds,
   so moving between pages renders from cache instead of waiting on the
   network — the single biggest browsing-speed win on mobile. */
const API_TTL_MS = 90 * 1000;
const _inflight = {};

async function apiCached(path) {
  if (_inflight[path]) return _inflight[path];
  const key = 'cs_api_' + path;
  try {
    const hit = JSON.parse(sessionStorage.getItem(key) || 'null');
    if (hit && Date.now() - hit.t < API_TTL_MS) return hit.d;
  } catch (e) {}

  _inflight[path] = (async () => {
    const res = await fetch(path);
    if (!res.ok) throw new Error('api not ready');
    const data = await res.json();
    try { sessionStorage.setItem(key, JSON.stringify({ t: Date.now(), d: data })); } catch (e) {}
    return data;
  })();
  try {
    return await _inflight[path];
  } finally {
    delete _inflight[path];
  }
}

async function getProducts() {
  try {
    return await apiCached('/api/products');
  } catch (e) {
    return SAMPLE_PRODUCTS; // placeholder fallback until D1 + Functions are wired up
  }
}

async function getSettings() {
  try {
    return await apiCached('/api/settings');
  } catch (e) {
    return {};
  }
}

async function getProduct(slug) {
  const products = await getProducts();
  return products.find(p => p.slug === slug);
}

function money(n) {
  return 'Rs. ' + Number(n).toLocaleString('en-PK');
}

function ttEvent(name, params) {
  if (window.ttq) {
    try { ttq.track(name, params); } catch (e) {}
  }
}

function cleanDescription(html) {
  if (!html) return '';
  return html.replace(/-{3,}/g, '<span class="desc-divider"></span>');
}

function priceHtml(p) {
  if (p.sale_price) {
    const discount = Math.round((1 - p.sale_price / p.price) * 100);
    return `<span class="regular">${money(p.price)}</span><span class="sale">${money(p.sale_price)}</span><span class="discount-badge">${discount}% OFF</span>`;
  }
  return `<span class="normal">${money(p.price)}</span>`;
}

/* ---------------- Cart (localStorage) ---------------- */
const CART_KEY = 'kapra_cart';

function getCart() {
  try { return JSON.parse(localStorage.getItem(CART_KEY)) || []; }
  catch (e) { return []; }
}

function saveCart(cart) {
  localStorage.setItem(CART_KEY, JSON.stringify(cart));
  updateCartBadge();
}

function addToCart(product, qty = 1) {
  const cart = getCart();
  const price = product.sale_price || product.price;
  const existing = cart.find(i => i.product_id === product.id);
  if (existing) {
    existing.qty += qty;
  } else {
    cart.push({
      product_id: product.id, name: product.name, qty, price,
      image_url: product.image_url
    });
  }
  saveCart(cart);
  ttEvent('AddToCart', {
    contents: [{ content_id: String(product.id), content_type: 'product', content_name: product.name, price, quantity: qty }],
    value: price * qty,
    currency: 'PKR'
  });
}

function updateCartQty(index, qty) {
  const cart = getCart();
  if (qty <= 0) { cart.splice(index, 1); }
  else { cart[index].qty = qty; }
  saveCart(cart);
}

function removeFromCart(index) {
  const cart = getCart();
  cart.splice(index, 1);
  saveCart(cart);
}

function cartTotal(cart) {
  return cart.reduce((sum, i) => sum + i.price * i.qty, 0);
}

function updateCartBadge() {
  const badges = document.querySelectorAll('.cart-count');
  if (!badges.length) return;
  const count = getCart().reduce((sum, i) => sum + i.qty, 0);
  badges.forEach(b => { b.textContent = count; });
}

document.addEventListener('DOMContentLoaded', () => {
  updateCartBadge();
  initMobileMenu();
  applySiteLogo();
  initHeaderCartIcon();
  ensureSearchOverlay();
  initFabricSwipe();
  ensureMobileTabBar();
  initTrustMarquee();
});

/* ---------------- Trust strip marquee ---------------- */
function initTrustMarquee() {
  const row = document.querySelector('.trust-strip-inner');
  if (!row || row.classList.contains('marquee')) return;
  const items = [...row.children];
  if (!items.length) return;

  const track = document.createElement('div');
  track.className = 'marquee-track';
  items.forEach(el => track.appendChild(el));
  // second copy makes the -50% loop seamless
  items.forEach(el => track.appendChild(el.cloneNode(true)));
  row.appendChild(track);
  row.classList.add('marquee');

  // ~55px per second: comfortable reading pace on any screen width
  requestAnimationFrame(() => {
    const distance = track.scrollWidth / 2;
    if (distance > 0) {
      row.style.setProperty('--marquee-duration', Math.max(18, Math.round(distance / 55)) + 's');
    }
  });
}

/* ---------------- Mobile bottom tab bar ---------------- */
function ensureMobileTabBar() {
  if (document.getElementById('mobile-tabbar')) return;
  if (document.querySelector('.admin-wrap')) return; // admin panel keeps its own layout

  // Product pages already carry a sticky add-to-cart bar — one bottom bar only.
  if (document.querySelector('.sticky-atc')) {
    document.body.classList.add('has-sticky-atc');
    return;
  }

  const file = (location.pathname.split('/').pop() || 'index.html');
  const isHome = file === '' || file === 'index.html';
  const isShop = file.indexOf('collection') === 0;
  const isCart = file.indexOf('cart') === 0 || file.indexOf('checkout') === 0;

  const nav = document.createElement('nav');
  nav.className = 'mobile-tabbar';
  nav.id = 'mobile-tabbar';
  nav.setAttribute('aria-label', 'Main');
  nav.innerHTML = `
    <a href="/index.html" class="${isHome ? 'active' : ''}">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M3 10.5 12 3l9 7.5"/><path d="M5 10v10h14V10"/></svg>
      <span>Home</span>
    </a>
    <a href="/collection.html" class="${isShop ? 'active' : ''}">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16"/><path d="M3 9h18M9 4v16"/></svg>
      <span>Shop</span>
    </a>
    <a href="#" id="tabbar-search">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
      <span>Search</span>
    </a>
    <a href="/cart.html" id="tabbar-bag" class="${isCart ? 'active' : ''}">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M6 2 3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4Z"/><path d="M3 6h18"/><path d="M16 10a4 4 0 0 1-8 0"/></svg>
      <span class="cart-count tab-badge">0</span>
      <span>Bag</span>
    </a>
  `;
  document.body.appendChild(nav);

  nav.querySelector('#tabbar-search').addEventListener('click', (e) => {
    e.preventDefault();
    openSearchOverlay();
  });
  // On cart/checkout the Bag tab navigates; elsewhere it opens the slide-up bag.
  if (!isCart) {
    nav.querySelector('#tabbar-bag').addEventListener('click', (e) => {
      e.preventDefault();
      sessionStorage.setItem('cs_return_url', window.location.href);
      openCartDrawer();
    });
  }
  updateCartBadge();
}

async function applySiteLogo() {
  const img = document.getElementById('site-logo-img');
  const txt = document.getElementById('site-logo-text');
  if (!img || !txt) return;
  try {
    const s = await getSettings();
    if (s.logo_url) {
      img.src = s.logo_url;
      img.style.display = '';
      txt.style.display = 'none';
    }
  } catch (e) {
    // keep text logo if settings can't load
  }
}

/* ---------------- Mobile nav drawer ---------------- */
/* ---------------- Confetti burst ---------------- */
/* ---------------- Slide-out cart drawer ---------------- */
function ensureCartDrawer() {
  if (document.getElementById('cart-drawer')) return;
  const backdrop = document.createElement('div');
  backdrop.className = 'cart-drawer-backdrop';
  backdrop.id = 'cart-drawer-backdrop';
  const drawer = document.createElement('div');
  drawer.className = 'cart-drawer';
  drawer.id = 'cart-drawer';
  drawer.innerHTML = `
    <div class="cart-drawer-head">
      <h3>Shopping Bag</h3>
      <button class="cart-drawer-close" id="cart-drawer-close" aria-label="Close">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
      </button>
    </div>
    <div class="cart-drawer-body" id="cart-drawer-body"></div>
    <div class="cart-drawer-foot" id="cart-drawer-foot"></div>
  `;
  document.body.appendChild(backdrop);
  document.body.appendChild(drawer);
  backdrop.addEventListener('click', closeCartDrawer);
  document.getElementById('cart-drawer-close').addEventListener('click', closeCartDrawer);
}

function closeCartDrawer() {
  document.getElementById('cart-drawer')?.classList.remove('open');
  document.getElementById('cart-drawer-backdrop')?.classList.remove('open');
}

function renderCartDrawer() {
  const cart = getCart();
  const body = document.getElementById('cart-drawer-body');
  const foot = document.getElementById('cart-drawer-foot');
  const FREE_DELIVERY_THRESHOLD = 3000;

  if (cart.length === 0) {
    body.innerHTML = `<div class="cart-drawer-empty">Your bag is empty.</div>`;
    foot.innerHTML = `<button class="cart-drawer-continue" onclick="continueShopping()">Continue Shopping</button>`;
    return;
  }

  const subtotal = cartTotal(cart);
  const remaining = FREE_DELIVERY_THRESHOLD - subtotal;
  const shippingHtml = remaining > 0
    ? `<div class="cart-drawer-shipping"><p>Add <b>${money(remaining)}</b> more for FREE delivery!</p></div>`
    : `<div class="cart-drawer-shipping unlocked"><p>✓ You've earned FREE delivery!</p></div>`;

  body.innerHTML = shippingHtml + cart.map((item, i) => `
    <div class="cart-drawer-item">
      <img src="${item.image_url}" alt="${item.name}">
      <div>
        <div class="name">${item.name}</div>
        <div class="price">${money(item.price)}</div>
        <div class="cart-drawer-qty">
          <button data-i="${i}" data-d="-1">−</button>
          <span>${item.qty}</span>
          <button data-i="${i}" data-d="1">+</button>
        </div>
      </div>
      <button class="cart-drawer-remove" data-remove="${i}" aria-label="Remove">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M3 6h18"/><path d="M8 6V4h8v2"/><path d="M19 6l-1 14H6L5 6"/></svg>
      </button>
    </div>
  `).join('');

  foot.innerHTML = `
    <div class="cart-drawer-subtotal"><span>Subtotal</span><span>${money(subtotal)}</span></div>
    <a href="/checkout.html" class="btn">Checkout</a>
    <a href="/cart.html" class="btn" style="background:transparent; border:1px solid var(--ink-green); color:var(--ink-green);">View Cart</a>
    <button class="cart-drawer-continue" onclick="continueShopping()">Continue Shopping</button>
  `;

  body.querySelectorAll('.cart-drawer-qty button').forEach(btn => {
    btn.addEventListener('click', () => {
      const i = Number(btn.dataset.i);
      const delta = Number(btn.dataset.d);
      updateCartQty(i, cart[i].qty + delta);
      renderCartDrawer();
    });
  });
  body.querySelectorAll('[data-remove]').forEach(btn => {
    btn.addEventListener('click', () => {
      removeFromCart(Number(btn.dataset.remove));
      renderCartDrawer();
    });
  });
}

function openCartDrawer() {
  ensureCartDrawer();
  renderCartDrawer();
  document.getElementById('cart-drawer').classList.add('open');
  document.getElementById('cart-drawer-backdrop').classList.add('open');
}

function continueShopping() {
  const returnUrl = sessionStorage.getItem('cs_return_url');
  if (returnUrl && returnUrl !== window.location.href) {
    window.location.href = returnUrl;
  } else {
    closeCartDrawer();
  }
}

document.addEventListener('DOMContentLoaded', ensureCartDrawer);

function fireConfetti() {
  const canvas = document.createElement('canvas');
  canvas.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:9999;';
  canvas.width = window.innerWidth;
  canvas.height = window.innerHeight;
  document.body.appendChild(canvas);
  const ctx = canvas.getContext('2d');
  const colors = ['#B8923E', '#0A0A0A', '#E2D2A6', '#FAFAF8'];
  const originX = window.innerWidth / 2;
  const originY = window.innerHeight * 0.35;
  const isMobile = window.innerWidth <= 720;
  const particleCount = isMobile ? 26 : 55;
  const maxFrames = isMobile ? 55 : 85;

  const particles = Array.from({ length: particleCount }, () => ({
    x: originX + (Math.random() - 0.5) * 60,
    y: originY,
    vx: (Math.random() - 0.5) * 9,
    vy: Math.random() * -9 - 3,
    size: Math.random() * 6 + 4,
    color: colors[Math.floor(Math.random() * colors.length)],
    rotation: Math.random() * 360,
    rotSpeed: (Math.random() - 0.5) * 22
  }));

  let frame = 0;
  function animate() {
    frame++;
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    particles.forEach(p => {
      p.x += p.vx;
      p.y += p.vy;
      p.vy += 0.3;
      p.rotation += p.rotSpeed;
      ctx.save();
      ctx.translate(p.x, p.y);
      ctx.rotate((p.rotation * Math.PI) / 180);
      ctx.fillStyle = p.color;
      ctx.fillRect(-p.size / 2, -p.size / 2, p.size, p.size);
      ctx.restore();
    });
    if (frame < maxFrames) {
      requestAnimationFrame(animate);
    } else {
      canvas.remove();
    }
  }
  animate();
}

function initMobileMenu() {
  const btn = document.getElementById('mobile-menu-btn');
  const drawer = document.getElementById('mobile-nav-drawer');
  const backdrop = document.getElementById('mobile-nav-backdrop');
  const closeBtn = document.getElementById('mobile-nav-close');
  if (!btn || !drawer || !backdrop) return;

  function openDrawer() { drawer.classList.add('open'); backdrop.classList.add('open'); }
  function closeDrawer() { drawer.classList.remove('open'); backdrop.classList.remove('open'); }

  btn.addEventListener('click', openDrawer);
  closeBtn?.addEventListener('click', closeDrawer);
  backdrop.addEventListener('click', closeDrawer);
  drawer.querySelectorAll('a').forEach(a => a.addEventListener('click', closeDrawer));

  loadNavTabs(closeDrawer);
}

/* ---------------- Dynamic hamburger menu: Men / Women tabs ---------------- */
let NAV_TABS_CACHE = null;

async function loadNavTabs(onLinkClick) {
  const switcher = document.getElementById('drawer-gender-switch');
  const linksBox = document.getElementById('drawer-links');
  if (!linksBox) return;

  try {
    if (!NAV_TABS_CACHE) {
      NAV_TABS_CACHE = await apiCached('/api/nav-tabs');
    }
    const tabs = Array.isArray(NAV_TABS_CACHE) ? NAV_TABS_CACHE : [];
    const hasMen = tabs.some(t => t.gender === 'men');
    const hasWomen = tabs.some(t => t.gender === 'women');

    if (!hasMen || !switcher) {
      // Only one gender in use (or switch not present on this page) — no toggle needed
      switcher && (switcher.style.display = hasMen && hasWomen ? 'flex' : 'none');
    } else {
      switcher.style.display = 'flex';
    }

    function renderGender(gender) {
      const list = tabs.filter(t => t.gender === gender || t.gender === 'all');
      if (list.length === 0) {
        linksBox.innerHTML = '<div class="drawer-links-empty">No categories added yet.</div>';
        return;
      }
      linksBox.innerHTML = list.map(t =>
        `<a class="drawer-tab-link" href="/collection.html?tab=${encodeURIComponent(t.slug)}">${t.label}</a>`
      ).join('');
      linksBox.querySelectorAll('a').forEach(a => a.addEventListener('click', () => onLinkClick && onLinkClick()));
    }

    let currentGender = hasWomen ? 'women' : (hasMen ? 'men' : 'all');
    renderGender(currentGender);

    if (switcher) {
      switcher.querySelectorAll('button').forEach(b => {
        b.classList.toggle('active', b.dataset.gender === currentGender);
        b.addEventListener('click', () => {
          switcher.querySelectorAll('button').forEach(x => x.classList.remove('active'));
          b.classList.add('active');
          renderGender(b.dataset.gender);
        });
      });
    }
  } catch (e) {
    linksBox.innerHTML = '';
  }
}

/* ---------------- Header cart icon: open the slide-out drawer ---------------- */
function initHeaderCartIcon() {
  const link = document.getElementById('header-cart-btn');
  if (!link) return;
  link.addEventListener('click', (e) => {
    e.preventDefault();
    openCartDrawer();
  });
}

/* ---------------- Search overlay ---------------- */
function ensureSearchOverlay() {
  const trigger = document.getElementById('header-search-btn');
  if (!trigger || document.getElementById('search-overlay')) {
    trigger?.addEventListener('click', openSearchOverlay);
    return;
  }
  const backdrop = document.createElement('div');
  backdrop.className = 'search-overlay-backdrop';
  backdrop.id = 'search-overlay-backdrop';
  const overlay = document.createElement('div');
  overlay.className = 'search-overlay';
  overlay.id = 'search-overlay';
  overlay.innerHTML = `
    <div class="search-overlay-bar">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
      <input type="text" class="search-overlay-input" id="search-overlay-input" placeholder="Search suits, fabrics..." autocomplete="off">
      <button class="search-overlay-close" id="search-overlay-close" aria-label="Close search">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
      </button>
    </div>
    <div class="search-overlay-results" id="search-overlay-results"></div>
  `;
  document.body.appendChild(backdrop);
  document.body.appendChild(overlay);

  backdrop.addEventListener('click', closeSearchOverlay);
  document.getElementById('search-overlay-close').addEventListener('click', closeSearchOverlay);

  const input = document.getElementById('search-overlay-input');
  let debounceTimer;
  input.addEventListener('input', () => {
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => runSearch(input.value), 180);
  });
  input.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeSearchOverlay(); });

  trigger.addEventListener('click', openSearchOverlay);
}

async function runSearch(q) {
  const results = document.getElementById('search-overlay-results');
  const query = q.trim().toLowerCase();
  if (!query) { results.innerHTML = ''; return; }
  const products = await getProducts();
  const matches = products.filter(p =>
    p.name.toLowerCase().includes(query) ||
    (p.fabric || '').toLowerCase().includes(query) ||
    (p.description || '').toLowerCase().includes(query)
  ).slice(0, 12);

  if (matches.length === 0) {
    results.innerHTML = `<div class="search-overlay-empty">No products found for "${q}"</div>`;
    return;
  }
  results.innerHTML = matches.map(p => `
    <a class="search-result-card" href="/product.html?slug=${p.slug}">
      <img src="${p.image_url}" alt="${p.name}" loading="lazy">
      <div class="search-result-name">${p.name}</div>
    </a>
  `).join('');
}

function openSearchOverlay() {
  document.getElementById('search-overlay')?.classList.add('open');
  document.getElementById('search-overlay-backdrop')?.classList.add('open');
  setTimeout(() => document.getElementById('search-overlay-input')?.focus(), 150);
}

function closeSearchOverlay() {
  document.getElementById('search-overlay')?.classList.remove('open');
  document.getElementById('search-overlay-backdrop')?.classList.remove('open');
}

/* ---------------- Fabric type icons (auto-assigned, no photo needed) ---------------- */
const FABRIC_ICONS = {
  swatch: '<svg viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"><rect x="8" y="8" width="20" height="20" rx="2" transform="rotate(-8 18 18)"/><rect x="18" y="18" width="20" height="20" rx="2" transform="rotate(8 28 28)"/></svg>',
  roll: '<svg viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><ellipse cx="14" cy="24" rx="6" ry="14"/><path d="M14 10c11 0 24 2 24 14s-13 14-24 14"/><path d="M14 10v28"/></svg>',
  thread: '<svg viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M15 9h18v6c0 4-6 6-6 9s6 5 6 9v6H15v-6c0-4 6-6 6-9s-6-5-6-9V9Z"/><path d="M17 9h14M17 39h14"/></svg>',
  fold: '<svg viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M8 16c6-4 10-4 16 0s10 4 16 0"/><path d="M8 24c6-4 10-4 16 0s10 4 16 0"/><path d="M8 32c6-4 10-4 16 0s10 4 16 0"/></svg>',
  loom: '<svg viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><path d="M10 10v28M18 10v28M26 10v28M34 10v28"/><path d="M8 14h32M8 22h32M8 30h32"/></svg>',
  pattern: '<svg viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"><path d="M24 8 34 18 24 28 14 18Z"/><path d="M24 22 34 32 24 42 14 32Z"/></svg>',
  stitch: '<svg viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><path d="M8 30c8-16 24-16 32 0" stroke-dasharray="4 4"/><circle cx="38" cy="12" r="2.4" fill="currentColor" stroke="none"/><path d="M38 12 30 20" stroke-dasharray="0"/></svg>',
  drape: '<svg viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><path d="M10 8c2 10-2 14 0 32M19 8c2 10-2 14 0 32M28 8c2 10-2 14 0 32M37 8c2 10-2 14 0 32"/></svg>'
};
function fabricIconSvg(key) {
  return FABRIC_ICONS[key] || FABRIC_ICONS.swatch;
}
function initFabricSwipe() {
  const track = document.getElementById('fabric-swipe-track');
  if (!track) return;
  syncFabricStripFit(track);
  if (track.dataset.swipeBound) return;
  track.dataset.swipeBound = '1';
  window.addEventListener('resize', () => syncFabricStripFit(track), { passive: true });

  let isDown = false, startX = 0, scrollStart = 0;

  track.addEventListener('mousedown', (e) => {
    isDown = true;
    track.classList.add('dragging');
    startX = e.pageX;
    scrollStart = track.scrollLeft;
  });
  window.addEventListener('mouseup', () => { isDown = false; track.classList.remove('dragging'); });
  window.addEventListener('mousemove', (e) => {
    if (!isDown) return;
    e.preventDefault();
    track.scrollLeft = scrollStart - (e.pageX - startX);
  });

  const prevBtn = document.getElementById('fabric-swipe-prev');
  const nextBtn = document.getElementById('fabric-swipe-next');
  const scrollByTile = (dir) => {
    const tile = track.querySelector('.category-tile');
    const amount = tile ? tile.getBoundingClientRect().width + 26 : 200;
    track.scrollBy({ left: dir * amount * 2, behavior: 'smooth' });
  };
  prevBtn?.addEventListener('click', () => scrollByTile(-1));
  nextBtn?.addEventListener('click', () => scrollByTile(1));
}

// Centre the fabric strip only while every tile fits on screen; once it
// overflows it must start at flex-start or the tiles that spill past the
// left edge can never be scrolled to.
function syncFabricStripFit(track) {
  if (!track) return;
  const fits = track.scrollWidth <= track.clientWidth + 1;
  track.classList.toggle('fits', fits);
  const wrap = track.closest('.fabric-swipe-wrap');
  if (wrap) {
    wrap.querySelectorAll('.fabric-swipe-arrow').forEach(a => {
      a.style.visibility = fits ? 'hidden' : '';
    });
  }
}

/* ---------------- Toast confirmation ---------------- */
function showToast(message) {
  let toast = document.getElementById('cart-toast');
  if (!toast) {
    toast = document.createElement('div');
    toast.id = 'cart-toast';
    toast.className = 'cart-toast';
    document.body.appendChild(toast);
  }
  toast.textContent = message;
  toast.classList.add('show');
  clearTimeout(window.__toastTimer);
  window.__toastTimer = setTimeout(() => toast.classList.remove('show'), 2200);
}
