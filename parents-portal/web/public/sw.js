/*
 * Service worker de l'application parents : la page est demandée au réseau d'abord (copie en cache en secours) ;
 * les fichiers /assets/ (noms avec empreinte) sont servis depuis le cache. L'API (données de la famille, photos,
 * factures) n'est jamais mise en cache : rien de personnel ne reste sur le téléphone après la déconnexion.
 */
const CACHE = "ticagou-parents-v2";
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

// --- Notifications (ADR 0018) ---------------------------------------------------------------
// Le contenu arrive chiffré du portail : titre (la crèche), phrase courte, page à ouvrir. Jamais de donnée de santé.
self.addEventListener("push", (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch {
    data = {};
  }
  event.waitUntil(self.registration.showNotification(data.title || "Ti’Cagou", {
    body: data.body || "Du nouveau de la crèche.",
    icon: "/icons/icon-256.png",
    badge: "/icons/icon-256.png",
    tag: data.tag,
    data: { url: typeof data.url === "string" && data.url.startsWith("/") ? data.url : "/" },
  }));
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const url = event.notification.data?.url ?? "/";
  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((windows) => {
      const open = windows.find((w) => new URL(w.url).origin === self.location.origin);
      if (open) return open.navigate(url).then((w) => (w ?? open).focus());
      return self.clients.openWindow(url);
    }),
  );
});
