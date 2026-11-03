import { install } from "./leapwire/index.js";
const mode = new URLSearchParams(location.search).get("mode");
const leapwire = mode === "leapwire" ? install() : null;
const get = async (url) => {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(url + " " + response.status);
  }
  return response.json();
};
const started = performance.now();
try {
  const session = await get("/api/session");
  const account = await get(`/api/users/${session.userId}`);
  const order = await get(`/api/orders/${account.latestOrderId}`);
  const [customer, lines] = await Promise.all([get(`/api/customers/${order.customerId}`), get(`/api/orders/${order.id}/lines`)]);
  const products = await get(`/api/products?ids=${lines.map((l) => l.productId).join(",")}`);
  const ms = performance.now() - started;
  document.getElementById("out").textContent = `${account.name}: order ${order.id} for ${customer.name}, ${products.length} products`;
  window.__result = { ms, user: account.username, customer: customer.name, products: products.map((p) => p.id), stats: leapwire ? leapwire.stats() : null };
} catch (error) {
  window.__result = { error: String(error) };
}
