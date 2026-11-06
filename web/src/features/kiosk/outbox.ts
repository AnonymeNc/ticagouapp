/**
 * File d'attente des opérations de la tablette (ADR 0005). Toute action y passe, en ligne comme hors
 * connexion : rangée d'abord (IndexedDB), puis envoyée dans l'ordre, avec reprise automatique tant que le
 * serveur ne répond pas. Le serveur applique chaque opération une seule fois (clientId).
 */
import { ApiError } from "@/lib/api";
import type { Operation, OperationResult } from "./api";
import { canSeal, seal, unseal, type Sealed } from "./secureStore";

export type QueueStore = {
  /** Opérations en attente, dans l'ordre de création. */
  all(): Promise<Operation[]>;
  add(op: Operation): Promise<void>;
  remove(clientIds: string[]): Promise<void>;
};

export type Rejection = { op: Operation; result: OperationResult };

export type OutboxState = {
  pending: Operation[];
  /** Envoyées et appliquées, en attente du prochain instantané du serveur. */
  acked: { op: Operation; at: number }[];
  online: boolean;
  sending: boolean;
  lastSyncAt: Date | null;
  rejected: Rejection[];
};

type Send = (ops: Operation[]) => Promise<{ results: OperationResult[] }>;

const BATCH = 100;
const RETRY_MS = [2_000, 5_000, 10_000, 30_000];

export class Outbox {
  private state: OutboxState = { pending: [], acked: [], online: true, sending: false, lastSyncAt: null, rejected: [] };
  private listeners = new Set<() => void>();
  private flushing: Promise<void> | null = null;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private failures = 0;
  private waiting = new Map<string, (result: OperationResult | null) => void>();

  constructor(
    private readonly store: QueueStore,
    private readonly send: Send,
  ) {}

  getState = () => this.state;

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  private set(patch: Partial<OutboxState>) {
    this.state = { ...this.state, ...patch };
    this.listeners.forEach((l) => l());
  }

  /** Recharge la file au démarrage (opérations restées sur la tablette) et tente de l'envoyer. */
  async load() {
    this.set({ pending: await this.store.all() });
    if (this.state.pending.length > 0) void this.flush();
  }

  /**
   * Range l'opération puis tente l'envoi. Renvoie son résultat si le serveur a répondu, null si elle reste
   * en attente (hors connexion).
   */
  async enqueue(op: Operation): Promise<OperationResult | null> {
    await this.store.add(op);
    this.set({ pending: [...this.state.pending, op] });
    const result = new Promise<OperationResult | null>((resolve) => this.waiting.set(op.clientId, resolve));
    await this.flush();
    if (this.waiting.has(op.clientId)) {
      this.waiting.get(op.clientId)!(null);
      this.waiting.delete(op.clientId);
    }
    return result;
  }

  /** Envoie la file ; un seul envoi à la fois, les appels concurrents attendent le suivant. */
  async flush(): Promise<void> {
    if (this.flushing) {
      await this.flushing;
      if (this.state.pending.length === 0) return;
    }
    this.flushing = this.run().finally(() => {
      this.flushing = null;
    });
    return this.flushing;
  }

  /** Bouton « Relancer » : envoi immédiat, sans attendre la prochaine reprise. */
  retryNow() {
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = null;
    void this.flush();
  }

  dismiss(clientId: string) {
    this.set({ rejected: this.state.rejected.filter((r) => r.op.clientId !== clientId) });
  }

  /** Un nouvel instantané du serveur intègre les opérations confirmées avant {@code since}. */
  forgetAckedBefore(since: number) {
    const acked = this.state.acked.filter((a) => a.at > since);
    if (acked.length !== this.state.acked.length) this.set({ acked });
  }

  private async run() {
    while (this.state.pending.length > 0) {
      const batch = this.state.pending.slice(0, BATCH);
      this.set({ sending: true });
      let results: OperationResult[];
      try {
        results = (await this.send(batch)).results;
      } catch (error) {
        if (error instanceof ApiError && error.status === 400) {
          // Lot mal formé : on écarte la première opération pour ne pas bloquer la file.
          results = [{ clientId: batch[0].clientId, outcome: "REFUSEE", code: error.code, message: error.message, needsReview: false }];
        } else {
          this.set({ sending: false, online: false });
          this.scheduleRetry();
          return;
        }
      }
      const done = new Set(results.map((r) => r.clientId));
      await this.store.remove([...done]);
      const now = Date.now();
      const rejected = [...this.state.rejected];
      const acked = [...this.state.acked];
      for (const result of results) {
        const op = batch.find((o) => o.clientId === result.clientId);
        if (!op) continue;
        if (result.outcome === "REFUSEE") rejected.push({ op, result });
        else acked.push({ op, at: now });
        const resolve = this.waiting.get(result.clientId);
        if (resolve) {
          resolve(result);
          this.waiting.delete(result.clientId);
        }
      }
      this.failures = 0;
      this.set({
        pending: this.state.pending.filter((o) => !done.has(o.clientId)),
        acked,
        rejected,
        online: true,
        sending: false,
        lastSyncAt: new Date(),
      });
      if (done.size === 0) return;
    }
    this.set({ online: true, sending: false });
  }

  private scheduleRetry() {
    if (this.retryTimer) return;
    const delay = RETRY_MS[Math.min(this.failures, RETRY_MS.length - 1)];
    this.failures++;
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      void this.flush();
    }, delay);
  }

  /** Marque le serveur joignable ou non (pastille de santé). */
  setOnline(online: boolean) {
    if (online === this.state.online) return;
    this.set({ online });
    if (online && this.state.pending.length > 0) this.retryNow();
  }
}

// --- Stockage ------------------------------------------------------------------

export function memoryStore(initial: Operation[] = []): QueueStore {
  let ops = [...initial];
  return {
    all: async () => [...ops],
    add: async (op) => {
      ops.push(op);
    },
    remove: async (ids) => {
      ops = ops.filter((o) => !ids.includes(o.clientId));
    },
  };
}

const DB_NAME = "ticagou-kiosk";
const STORE = "outbox";

/** File persistante et chiffrée (une opération peut porter un PIN) : survit à un redémarrage de la tablette. */
export function indexedDbStore(): QueueStore {
  let db: Promise<IDBDatabase> | null = null;
  const open = () => {
    db ??= new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, 1);
      request.onupgradeneeded = () => request.result.createObjectStore(STORE, { keyPath: "seq", autoIncrement: true });
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    return db;
  };
  const tx = async <T>(mode: IDBTransactionMode, run: (store: IDBObjectStore) => IDBRequest<T> | void) => {
    const database = await open();
    return new Promise<T | undefined>((resolve, reject) => {
      const transaction = database.transaction(STORE, mode);
      const request = run(transaction.objectStore(STORE));
      transaction.oncomplete = () => resolve(request ? request.result : undefined);
      transaction.onerror = () => reject(transaction.error);
    });
  };
  return {
    all: async () => {
      const rows = (await tx("readonly", (s) => s.getAll())) as ({ seq: number; clientId: string } & Sealed)[] | undefined;
      return Promise.all((rows ?? []).sort((a, b) => a.seq - b.seq).map((r) => unseal<Operation>(r)));
    },
    add: async (op) => {
      const sealed = await seal(op);
      await tx("readwrite", (s) => s.add({ clientId: op.clientId, ...sealed }));
    },
    remove: async (ids) => {
      if (ids.length === 0) return;
      await tx("readwrite", (s) => {
        const cursor = s.openCursor();
        cursor.onsuccess = () => {
          const current = cursor.result;
          if (!current) return;
          if (ids.includes((current.value as { clientId: string }).clientId)) current.delete();
          current.continue();
        };
      });
    },
  };
}

/** IndexedDB chiffrée si disponible (navigateur), mémoire sinon (tests, navigation privée stricte) : jamais en clair. */
export function defaultStore(): QueueStore {
  try {
    if (canSeal()) return indexedDbStore();
  } catch {
    // Accès refusé : file en mémoire, perdue au rechargement.
  }
  return memoryStore();
}
