/*
 * Service worker de l'application parents : la page est demandée au réseau d'abord (copie en cache en secours) ;
 * les fichiers /assets/ (noms avec empreinte) sont servis depuis le cache. L'API (données de la famille, photos,
 * factures) n'est jamais mise en cache : rien de personnel ne reste sur le téléphone après la déconnexion.
 */
const CACHE = "ticagou-parents-v1";
const SHELL = ["/", "/manifest.webmanifest", "/icons/icon-256.png", "/icons/icon-512.png"];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== "GET" || url.origin !== self.location.origin || url.pathname.startsWith("/api/")) return;
  if (url.pathname.startsWith("/assets/")) {
    event.respondWith(
      caches.match(request).then((cached) => cached ?? fetch(request).then((response) => {
        if (response.ok) caches.open(CACHE).then((cache) => cache.put(request, response.clone()));
        return response;
      })),
    );
    return;
  }
  if (request.mode === "navigate") {
    event.respondWith(fetch(request).catch(() => caches.match("/")));
  }
});
