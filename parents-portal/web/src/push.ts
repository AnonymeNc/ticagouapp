/** Notifications sur le téléphone (ADR 0018) : abonnement Web Push de cet appareil, via le service worker. */
import { portal } from "./api";

export type PushCategory = "PHOTOS" | "MESSAGES" | "ACTUALITES" | "DOCUMENTS" | "FACTURES";

export const pushCategories: { id: PushCategory; label: string; hint: string }[] = [
  { id: "PHOTOS", label: "Photos", hint: "Une nouvelle photo de votre enfant" },
  { id: "MESSAGES", label: "Messages", hint: "Un message de l’équipe ou de la direction" },
  { id: "ACTUALITES", label: "Actualités", hint: "Fêtes, sorties, menus" },
  { id: "DOCUMENTS", label: "Documents à signer", hint: "Contrat, avenant, autorisation" },
  { id: "FACTURES", label: "Factures", hint: "Une nouvelle facture ou un avoir" },
];

/** Factures décochées par défaut (ADR 0018). */
export const defaultCategories: PushCategory[] = ["PHOTOS", "MESSAGES", "ACTUALITES", "DOCUMENTS"];

export type PushSupport = "ok" | "unsupported" | "ios-install" | "denied";

export function pushSupport(): PushSupport {
  const ios = /iPad|iPhone|iPod/.test(navigator.userAgent);
  const standalone = window.matchMedia?.("(display-mode: standalone)").matches
    || (navigator as Navigator & { standalone?: boolean }).standalone === true;
  if (ios && !standalone) return "ios-install";
  if (!("serviceWorker" in navigator) || !("PushManager" in window) || !("Notification" in window)) return "unsupported";
  if (Notification.permission === "denied") return "denied";
  return "ok";
}

async function registration() {
  const existing = await navigator.serviceWorker.getRegistration();
  return existing ?? navigator.serviceWorker.register("/sw.js");
}

export async function currentSubscription() {
  if (pushSupport() !== "ok") return null;
  return (await registration()).pushManager.getSubscription();
}

function keyBytes(base64Url: string) {
  const base64 = base64Url.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(base64Url.length / 4) * 4, "=");
  return Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
}

/** Demande l'autorisation, abonne l'appareil et enregistre ses catégories ; faux si le parent refuse. */
export async function enablePush(categories: PushCategory[]) {
  if ((await Notification.requestPermission()) !== "granted") return false;
  const reg = await registration();
  let subscription = await reg.pushManager.getSubscription();
  if (!subscription) {
    const { publicKey } = await portal.pushState();
    subscription = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(publicKey) });
  }
  await saveCategories(subscription, categories);
  return true;
}

export async function saveCategories(subscription: PushSubscription, categories: PushCategory[]) {
  const json = subscription.toJSON();
  await portal.subscribePush({ endpoint: subscription.endpoint, p256dh: json.keys?.p256dh ?? "", auth: json.keys?.auth ?? "", categories });
}

/** Cet appareil n'est plus prévenu (désactivation, déconnexion). */
export async function disablePush() {
  const subscription = await currentSubscription().catch(() => null);
  if (!subscription) return;
  await portal.unsubscribePush(subscription.endpoint).catch(() => {});
  await subscription.unsubscribe().catch(() => false);
}
