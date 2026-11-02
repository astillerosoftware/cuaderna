// Chispa: permite abrir la app sin cobertura. Nunca guarda datos de OneDrive ni de Microsoft.
const CACHE = "chispa-v79";
const FICHEROS = ["./", "./manifest.webmanifest", "./icon-192.png", "./icon-512.png", "./astillero.svg", "./servidor.js", "./google.html"];
self.addEventListener("install", e => { e.waitUntil(caches.open(CACHE).then(c => c.addAll(FICHEROS.map(f => new Request(f, {cache: "reload"})))).then(() => self.skipWaiting())); });
self.addEventListener("activate", e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
// Primero la red, preguntando siempre al servidor si hay versión nueva (GitHub Pages deja guardar 10 min); sin cobertura, la copia guardada
self.addEventListener("fetch", e => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== location.origin) return;
  const clave = url.origin + url.pathname;
  e.respondWith(
    fetch(e.request.url, {cache: "no-cache", credentials: "same-origin"}).then(r => { if (r.ok){ const copia = r.clone(); caches.open(CACHE).then(c => c.put(clave, copia)); } return r; })
      .catch(() => caches.match(clave).then(r => r || caches.match("./")))
  );
});
