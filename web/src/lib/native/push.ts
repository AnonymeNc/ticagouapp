/**
 * Notifications natives de l'application Direction (ADR 0026) : jeton APNs (iPhone) ou FCM (Android) donné par le
 * système, inscrit auprès de l'instance de la crèche courante ; désinscrit à la déconnexion et à l'oubli de la crèche.
 * Toucher une notification ouvre l'écran qu'elle désigne (« /presences?depart=… »).
 */
import { api } from "@/lib/api";
import { vaultKey } from "./creches";
import { hasNativePlugins, isNativeApp, nativePlatform } from "./platform";
import { vault } from "./vault";

export type Category = "DEPART" | "ENCADREMENT" | "SAUVEGARDE" | "TABLETTE";
export type DeviceSettings = { platform: "IOS" | "ANDROID"; app: string; categories: Category[] };

export const categoryLabels: Record<Category, { title: string; hint: string }> = {
  DEPART: { title: "Départ bloqué à valider", hint: "Dès qu’une tablette demande votre accord" },
  ENCADREMENT: { title: "Encadrement insuffisant", hint: "Après quelques minutes au-dessus du barème" },
  SAUVEGARDE: { title: "Sauvegarde en échec", hint: "Le matin, si la nuit a échoué" },
  TABLETTE: { title: "Tablette hors connexion", hint: "Pendant l’ouverture, au-delà de 30 minutes" },
};

/** Jeton de notification de ce téléphone pour la crèche courante (gardé dans le coffre). */
export async function storedPushToken(): Promise<string | null> {
  if (!isNativeApp()) return null;
  return vault().get(vaultKey("notifications")).catch(() => null);
}

let listening = false;

/**
 * Demande l'autorisation (une fois), récupère le jeton et l'inscrit. Sans plugin (navigateur, simulation) : rien.
 * `onOpen` reçoit le chemin d'une notification touchée.
 */
export async function enablePushNotifications(onOpen: (link: string) => void): Promise<"granted" | "denied" | "unavailable"> {
  if (!isNativeApp() || !hasNativePlugins()) return "unavailable";
  const { PushNotifications } = await import("@capacitor/push-notifications");
  let permission = await PushNotifications.checkPermissions();
  if (permission.receive === "prompt" || permission.receive === "prompt-with-rationale") {
    permission = await PushNotifications.requestPermissions();
  }
  if (permission.receive !== "granted") return "denied";
  if (!listening) {
    listening = true;
    await PushNotifications.addListener("registration", ({ value }) => void registerToken(value));
    await PushNotifications.addListener("pushNotificationActionPerformed", ({ notification }) => {
      const link = (notification.data as { link?: string } | undefined)?.link;
      if (link && link.startsWith("/")) onOpen(link);
    });
  }
  await PushNotifications.register();
  return "granted";
}

async function registerToken(token: string, categories?: Category[]) {
  const platform = nativePlatform() === "ios" ? "IOS" : "ANDROID";
  await api<DeviceSettings>("/notifications/device", { method: "PUT", body: { platform, token, categories } });
  await vault().set(vaultKey("notifications"), token);
}

export async function pushSettings(): Promise<DeviceSettings | null> {
  const token = await storedPushToken();
  return token ? api<DeviceSettings>(`/notifications/device?token=${encodeURIComponent(token)}`) : null;
}

export async function savePushCategories(categories: Category[]): Promise<void> {
  const token = await storedPushToken();
  if (token) await registerToken(token, categories);
}

/** Déconnexion, oubli de la crèche : ce téléphone ne reçoit plus les alertes de cette crèche. */
export async function disablePushNotifications(): Promise<void> {
  const token = await storedPushToken();
  if (!token) return;
  await api(`/notifications/device?token=${encodeURIComponent(token)}`, { method: "DELETE" }).catch(() => {});
  await vault().remove(vaultKey("notifications")).catch(() => {});
}
