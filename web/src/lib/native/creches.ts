/**
 * Crèches connues de l'application (ADR 0025). Une instance par crèche : l'application doit savoir à quelle adresse
 * parler. La liste (nom, commune, adresse) n'a rien de sensible et va dans Preferences ; tout le reste (session,
 * identifiant de poste) est rangé par crèche dans le coffre, sous la clé `<id>.<nom>`.
 * Dans le navigateur, il n'y a qu'une crèche : celle de l'instance qui sert la page (adresse relative).
 */
import { isNativeApp, hasNativePlugins } from "./platform";
import { vault } from "./vault";

export type Creche = {
  id: string;
  nom: string;
  commune: string | null;
  /** Adresse publique de l'instance, sans barre finale : « https://ptits-niaoulis.ticagou.nc ». */
  adresse: string;
  /** Code de l'annuaire (6 caractères), s'il a servi. */
  code: string | null;
  /** ISO 8601. */
  derniereUtilisation: string;
};

const LIST_KEY = "ticagou.creches";
const CURRENT_KEY = "ticagou.creche";
/** Secrets rangés par crèche dans le coffre, effacés quand on l'oublie. */
export const VAULT_NAMES = ["session", "poste", "appareil"] as const;

let creches: Creche[] = [];
let currentId: string | null = null;
const listeners = new Set<() => void>();

type Store = { get(key: string): Promise<string | null>; set(key: string, value: string): Promise<void> };

function preferences(): Store {
  if (hasNativePlugins()) {
    return {
      get: async (key) => (await (await import("@capacitor/preferences")).Preferences.get({ key })).value,
      set: async (key, value) => (await import("@capacitor/preferences")).Preferences.set({ key, value }),
    };
  }
  // Simulation dans le navigateur : la liste (non sensible) survit au rechargement.
  return {
    get: async (key) => {
      try {
        return window.localStorage.getItem(key);
      } catch {
        return null;
      }
    },
    set: async (key, value) => {
      try {
        window.localStorage.setItem(key, value);
      } catch {
        // Stockage indisponible : la liste reste en mémoire.
      }
    },
  };
}

let store: Store | null = null;
const prefs = () => (store ??= preferences());

/** Tests : stockage de remplacement et remise à zéro. */
export function resetCreches(replacement: Store | null = null) {
  store = replacement;
  creches = [];
  currentId = null;
}

async function save() {
  await prefs().set(LIST_KEY, JSON.stringify(creches));
  await prefs().set(CURRENT_KEY, currentId ?? "");
  listeners.forEach((listener) => listener());
}

/** Lu une fois au démarrage de l'application, avant le premier rendu. */
export async function loadCreches(): Promise<void> {
  if (!isNativeApp()) return;
  try {
    const raw = await prefs().get(LIST_KEY);
    creches = raw ? (JSON.parse(raw) as Creche[]) : [];
  } catch {
    creches = [];
  }
  const id = await prefs().get(CURRENT_KEY);
  currentId = creches.some((c) => c.id === id) ? id : null;
}

export function listCreches(): Creche[] {
  return creches;
}

export function currentCreche(): Creche | null {
  return creches.find((c) => c.id === currentId) ?? null;
}

export function subscribeCreches(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** Préfixe des appels à l'API : vide dans le navigateur (même origine), adresse de la crèche dans l'application. */
export function apiBase(): string {
  return isNativeApp() ? (currentCreche()?.adresse ?? "") : "";
}

/** Clé du coffre propre à la crèche courante (« 3f2…​.session ») ; dans le navigateur, le nom seul. */
export function vaultKey(name: (typeof VAULT_NAMES)[number]): string {
  const current = currentCreche();
  return current ? `${current.id}.${name}` : name;
}

/**
 * « ptits-niaoulis.ticagou.nc/ » → « https://ptits-niaoulis.ticagou.nc ». https obligatoire, sauf vers le poste local
 * en développement. Renvoie null pour une adresse invalide.
 */
export function normalizeAddress(input: string, allowHttpLocal = import.meta.env.DEV): string | null {
  let text = input.trim();
  if (!text) return null;
  if (!/^[a-z]+:\/\//i.test(text)) text = `https://${text}`;
  let url: URL;
  try {
    url = new URL(text);
  } catch {
    return null;
  }
  const local = url.hostname === "localhost" || url.hostname === "127.0.0.1";
  if (url.protocol !== "https:" && !(url.protocol === "http:" && local && allowHttpLocal)) return null;
  if (url.username || url.password) return null;
  return url.origin;
}

/** Ajoute (ou retrouve, par son adresse) une crèche et en fait la crèche courante. */
export async function chooseCreche(input: { nom: string; commune: string | null; adresse: string; code: string | null }): Promise<Creche> {
  const now = new Date().toISOString();
  let creche = creches.find((c) => c.adresse === input.adresse);
  if (creche) {
    creche = { ...creche, nom: input.nom, commune: input.commune ?? creche.commune, code: input.code ?? creche.code, derniereUtilisation: now };
    creches = creches.map((c) => (c.id === creche!.id ? creche! : c));
  } else {
    creche = { id: crypto.randomUUID(), ...input, derniereUtilisation: now };
    creches = [...creches, creche];
  }
  currentId = creche.id;
  await save();
  return creche;
}

export async function switchCreche(id: string): Promise<void> {
  if (!creches.some((c) => c.id === id)) return;
  currentId = id;
  creches = creches.map((c) => (c.id === id ? { ...c, derniereUtilisation: new Date().toISOString() } : c));
  await save();
}

/** Efface du téléphone tout ce qui concerne la crèche : secrets du coffre, puis la crèche elle-même. */
export async function forgetCreche(id: string): Promise<void> {
  await Promise.all(VAULT_NAMES.map((name) => vault().remove(`${id}.${name}`)));
  creches = creches.filter((c) => c.id !== id);
  if (currentId === id) currentId = null;
  await save();
}
