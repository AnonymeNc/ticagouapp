/**
 * Notifications sur le téléphone : abonnement Web Push de cet appareil, via le service worker (PWA, ADR 0018), ou jeton
 * APNs / FCM de l’application Ti’Cagou Parents (ADR 0026).
 */
import { portal } from "./api";
import { IS_APP, hasPlugins, vault } from "./native";

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
  if (IS_APP) return hasPlugins() ? "ok" : "unsupported";
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

// --- Application native ------------------------------------------------------------------------

/** Appareil abonné : identifiant (adresse Web Push ou jeton natif) et catégories choisies. */
export type CurrentPush = { id: string; categories: PushCategory[] };

export async function currentPush(): Promise<CurrentPush | null> {
  if (IS_APP) {
    const token = await vault.get("push").catch(() => null);
    if (!token) return null;
    const state = await portal.nativePushState(token);
    return state.categories ? { id: token, categories: state.categories as PushCategory[] } : null;
  }
  const subscription = await currentSubscription();
  if (!subscription) return null;
  const state = await portal.pushState(subscription.endpoint);
  return { id: subscription.endpoint, categories: (state.categories ?? defaultCategories) as PushCategory[] };
}

async function nativeToken(): Promise<string | null> {
  const { PushNotifications } = await import("@capacitor/push-notifications");
  let permission = await PushNotifications.checkPermissions();
  if (permission.receive !== "granted") permission = await PushNotifications.requestPermissions();
  if (permission.receive !== "granted") return null;
  return new Promise((resolve) => {
    void PushNotifications.addListener("registration", ({ value }) => resolve(value));
    void PushNotifications.addListener("registrationError", () => resolve(null));
    void PushNotifications.register();
  });
}

/** Active les notifications sur ce téléphone, quel que soit le mode ; faux si le parent refuse. */
export async function enableDevicePush(categories: PushCategory[]): Promise<boolean> {
  if (!IS_APP) return enablePush(categories);
  const token = await nativeToken();
  if (!token) return false;
  const { Capacitor } = await import("@capacitor/core");
  await portal.registerNativePush({ platform: Capacitor.getPlatform() === "ios" ? "IOS" : "ANDROID", token, categories });
  await vault.set("push", token);
  return true;
}

export async function saveDeviceCategories(current: CurrentPush, categories: PushCategory[]): Promise<void> {
  if (IS_APP) {
    const { Capacitor } = await import("@capacitor/core");
    await portal.registerNativePush({ platform: Capacitor.getPlatform() === "ios" ? "IOS" : "ANDROID", token: current.id, categories });
    return;
  }
  const subscription = await currentSubscription();
  if (subscription) await saveCategories(subscription, categories);
}

/** Ce téléphone n’est plus prévenu (désactivation, déconnexion, suppression du compte). */
export async function disableDevicePush(): Promise<void> {
  if (!IS_APP) return disablePush();
  const token = await vault.get("push").catch(() => null);
  if (!token) return;
  await portal.unregisterNativePush(token).catch(() => {});
  await vault.remove("push").catch(() => {});
}
