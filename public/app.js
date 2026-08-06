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

async function getProducts() {
  try {
    const res = await fetch('/api/products');
    if (!res.ok) throw new Error('api not ready');
    return await res.json();
  } catch (e) {
    return SAMPLE_PRODUCTS; // placeholder fallback until D1 + Functions are wired up
  }
}

async function getProduct(slug) {
  const products = await getProducts();
  return products.find(p => p.slug === slug);
}

function money(n) {
  return 'Rs. ' + Number(n).toLocaleString('en-PK');
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
  const badge = document.querySelector('.cart-count');
  if (!badge) return;
  const count = getCart().reduce((sum, i) => sum + i.qty, 0);
  badge.textContent = count;
}

document.addEventListener('DOMContentLoaded', () => {
  updateCartBadge();
  initMobileMenu();
  applySiteLogo();
});

async function applySiteLogo() {
  const img = document.getElementById('site-logo-img');
  const txt = document.getElementById('site-logo-text');
  if (!img || !txt) return;
  try {
    const res = await fetch('/api/settings');
    const s = await res.json();
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

  const particles = Array.from({ length: 55 }, () => ({
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
    if (frame < 85) {
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
