/**
 * Chiffrement du stockage de la tablette (ADR 0013). Les opérations en attente peuvent porter un PIN et l'instantané du
 * jour contient des données de santé : ils ne sont écrits que chiffrés (AES-256-GCM). La clé est non extractible :
 * le navigateur s'en sert sans jamais la donner, et une copie des fichiers de la tablette ne contient pas de clé lisible.
 */
const DB_NAME = "ticagou-kiosk-keys";
const STORE = "keys";
const DATA = "data";
const KEY_ID = "local";

export type Sealed = { iv: Uint8Array<ArrayBuffer>; data: ArrayBuffer };

export function canSeal(): boolean {
  return typeof indexedDB !== "undefined" && typeof crypto !== "undefined" && crypto.subtle !== undefined;
}

let key: Promise<CryptoKey> | null = null;

function openKeys(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => {
      request.result.createObjectStore(STORE);
      request.result.createObjectStore(DATA);
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function loadKey(): Promise<CryptoKey> {
  key ??= (async () => {
    const db = await openKeys();
    const existing = await new Promise<CryptoKey | undefined>((resolve, reject) => {
      const request = db.transaction(STORE).objectStore(STORE).get(KEY_ID);
      request.onsuccess = () => resolve(request.result as CryptoKey | undefined);
      request.onerror = () => reject(request.error);
    });
    if (existing) return existing;
    const created = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
    await new Promise<void>((resolve, reject) => {
      const transaction = db.transaction(STORE, "readwrite");
      transaction.objectStore(STORE).put(created, KEY_ID);
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
    return created;
  })();
  return key;
}

export async function seal(value: unknown): Promise<Sealed> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const data = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await loadKey(), new TextEncoder().encode(JSON.stringify(value)));
  return { iv, data };
}

export async function unseal<T>(sealed: Sealed): Promise<T> {
  const clear = await crypto.subtle.decrypt({ name: "AES-GCM", iv: sealed.iv }, await loadKey(), sealed.data);
  return JSON.parse(new TextDecoder().decode(clear)) as T;
}

/** Sans IndexedDB (tests, navigation privée stricte) : en mémoire, jamais en clair sur le disque. */
const memory = new Map<string, unknown>();

function dataStore<T>(mode: IDBTransactionMode, run: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return openKeys().then(
    (db) =>
      new Promise<T>((resolve, reject) => {
        const request = run(db.transaction(DATA, mode).objectStore(DATA));
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
      }),
  );
}

export async function saveSealed(name: string, value: unknown): Promise<void> {
  if (!canSeal()) {
    memory.set(name, value);
    return;
  }
  const sealed = await seal(value);
  await dataStore("readwrite", (s) => s.put(sealed, name));
}

export async function loadSealed<T>(name: string): Promise<T | undefined> {
  if (!canSeal()) return memory.get(name) as T | undefined;
  const sealed = await dataStore<Sealed | undefined>("readonly", (s) => s.get(name));
  return sealed ? unseal<T>(sealed) : undefined;
}

export async function deleteSealed(name: string): Promise<void> {
  if (!canSeal()) {
    memory.delete(name);
    return;
  }
  await dataStore("readwrite", (s) => s.delete(name));
}
