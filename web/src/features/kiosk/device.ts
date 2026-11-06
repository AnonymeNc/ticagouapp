/**
 * Tablette appairée (ADR 0005) : secret gardé dans le stockage local, échangé contre un jeton d'appareil
 * de courte durée. Le jeton sert à la file d'attente, à la connexion par PIN et au flux temps réel.
 */
import { api, ApiError, registerDeviceHooks } from "@/lib/api";

const STORAGE_KEY = "ticagou.device";

export type PairedDevice = { deviceId: string; secret: string; name: string; structureName: string };

type DeviceSession = {
  accessToken: string;
  expiresIn: number;
  deviceId: string;
  name: string;
  structureId: string;
  structureName: string;
};

type Listener = () => void;

let accessToken: string | null = null;
let inFlight: Promise<boolean> | null = null;
const listeners = new Set<Listener>();

function storage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

let lastRaw: string | null = null;
let lastDevice: PairedDevice | null = null;

/** Même objet tant que le stockage ne change pas (lu par useSyncExternalStore à chaque rendu). */
export function getDevice(): PairedDevice | null {
  try {
    const raw = storage()?.getItem(STORAGE_KEY) ?? null;
    if (raw !== lastRaw) {
      lastRaw = raw;
      lastDevice = raw ? (JSON.parse(raw) as PairedDevice) : null;
    }
    return lastDevice;
  } catch {
    return null;
  }
}

function setDevice(device: PairedDevice | null) {
  try {
    if (device) storage()?.setItem(STORAGE_KEY, JSON.stringify(device));
    else storage()?.removeItem(STORAGE_KEY);
  } catch {
    // Stockage indisponible : la tablette devra être appairée à nouveau.
  }
  accessToken = null;
  listeners.forEach((l) => l());
}

/** Notifie l'appairage ou sa perte (tablette retirée par la direction). */
export function subscribeDevice(listener: Listener) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export async function pairDevice(code: string): Promise<PairedDevice> {
  const paired = await api<{ deviceId: string; secret: string; name: string; structureName: string }>(
    "/devices/pair",
    { method: "POST", body: { code }, auth: false },
  );
  setDevice(paired);
  return paired;
}

/** Oublie la tablette (retirée par la direction, ou secret remplacé par un nouvel appairage). */
export function forgetDevice() {
  setDevice(null);
}

/**
 * Nouveau jeton d'appareil. Les appels simultanés partagent la requête ; un 401 du serveur signifie que la
 * tablette n'est plus appairée, une erreur réseau qu'il est injoignable (le secret est gardé).
 */
export function refreshDeviceToken(): Promise<boolean> {
  const device = getDevice();
  if (!device) return Promise.resolve(false);
  if (!inFlight) {
    inFlight = api<DeviceSession>("/devices/session", {
      method: "POST",
      body: { deviceId: device.deviceId, secret: device.secret },
      auth: false,
    })
      .then((session) => {
        accessToken = session.accessToken;
        if (session.name !== device.name || session.structureName !== device.structureName) {
          storage()?.setItem(STORAGE_KEY, JSON.stringify({ ...device, name: session.name, structureName: session.structureName }));
        }
        return true;
      })
      .catch((error) => {
        if (error instanceof ApiError && error.status === 401) forgetDevice();
        if (error instanceof ApiError) return false;
        throw error;
      })
      .finally(() => {
        inFlight = null;
      });
  }
  return inFlight;
}

export function getDeviceToken() {
  return accessToken;
}

registerDeviceHooks({
  getAccessToken: getDeviceToken,
  refresh: () => refreshDeviceToken().catch(() => false),
});
