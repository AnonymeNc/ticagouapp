/**
 * Coffre des secrets de l'application (ADR 0024) : Keychain sur iOS (« cet appareil seulement, une fois déverrouillé »),
 * Keystore sur Android. Jamais dans localStorage ni Preferences. Hors application native (tests, simulation), le coffre
 * est en mémoire : rien n'est écrit en clair sur le disque.
 */
import { hasNativePlugins } from "./platform";

export interface Vault {
  get(key: string): Promise<string | null>;
  set(key: string, value: string): Promise<void>;
  remove(key: string): Promise<void>;
}

/** Coffre en mémoire : tests et simulation dans le navigateur. */
export function memoryVault(): Vault {
  const values = new Map<string, string>();
  return {
    get: async (key) => values.get(key) ?? null,
    set: async (key, value) => {
      values.set(key, value);
    },
    remove: async (key) => {
      values.delete(key);
    },
  };
}

function nativeVault(): Vault {
  let ready: Promise<typeof import("@aparajita/capacitor-secure-storage")> | null = null;
  const plugin = () => {
    ready ??= import("@aparajita/capacitor-secure-storage").then(async (module) => {
      await module.SecureStorage.setSynchronize(false);
      await module.SecureStorage.setDefaultKeychainAccess(module.KeychainAccess.whenUnlockedThisDeviceOnly);
      await module.SecureStorage.setKeyPrefix("ticagou.");
      return module;
    });
    return ready;
  };
  return {
    get: async (key) => (await plugin()).SecureStorage.getItem(key),
    set: async (key, value) => (await plugin()).SecureStorage.setItem(key, value),
    remove: async (key) => (await plugin()).SecureStorage.removeItem(key),
  };
}

let current: Vault | null = null;

export function vault(): Vault {
  current ??= hasNativePlugins() ? nativeVault() : memoryVault();
  return current;
}

/** Tests : coffre de remplacement. */
export function setVault(replacement: Vault | null) {
  current = replacement;
}
