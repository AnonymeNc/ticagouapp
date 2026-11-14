/**
 * Trouver sa crèche (ADR 0025) : par le code de l'annuaire, par le QR de l'instance, ou par son adresse. Dans tous les
 * cas, l'instance est interrogée (`/api/v1/app/compat`) pour afficher son nom avant de s'y connecter.
 */
import { normalizeAddress } from "./creches";

/** Annuaire publié par l'hôte (creche.sh annuaire), un fichier par code. */
export const ANNUAIRE_URL = (import.meta.env.VITE_TICAGOU_ANNUAIRE || "https://annuaire.ticagou.nc").replace(/\/$/, "");

/** Alphabet des codes : ni 0, O, 1, I ni L. */
const CODE = /^[A-HJKMNP-Z2-9]{6}$/;

export type FoundCreche = { nom: string; commune: string | null; adresse: string; code: string | null };

export class CrecheLookupError extends Error {}

/** « nk7 q2m » → « NK7Q2M » ; null si ce n'est pas un code possible. */
export function normalizeCode(input: string): string | null {
  const code = input.replace(/[\s-]/g, "").toUpperCase();
  return CODE.test(code) ? code : null;
}

/** Texte d'un QR : l'adresse de l'instance (« https://…/app ») ou un code seul. */
export function parseQr(text: string): { adresse: string } | { code: string } | null {
  const code = normalizeCode(text);
  if (code) return { code };
  if (!/^https?:\/\//i.test(text.trim())) return null;
  const adresse = normalizeAddress(text);
  return adresse ? { adresse } : null;
}

async function getJson<T>(url: string, signal?: AbortSignal): Promise<T> {
  let response: Response;
  try {
    response = await fetch(url, { headers: { Accept: "application/json" }, signal });
  } catch {
    throw new CrecheLookupError("Pas de connexion internet : réessayez dans un instant.");
  }
  if (!response.ok) throw new CrecheLookupError(response.status === 404 ? "introuvable" : `Erreur ${response.status}`);
  return (await response.json()) as T;
}

/** Fiche de l'annuaire pour ce code. */
export async function resolveCode(code: string, signal?: AbortSignal): Promise<FoundCreche> {
  try {
    const entry = await getJson<{ nom: string; commune?: string | null; adresse: string }>(`${ANNUAIRE_URL}/v1/${code}.json`, signal);
    const adresse = normalizeAddress(entry.adresse, false);
    if (!adresse) throw new CrecheLookupError("Adresse de la crèche invalide dans l’annuaire.");
    return { nom: entry.nom, commune: entry.commune || null, adresse, code };
  } catch (error) {
    if (error instanceof CrecheLookupError && error.message === "introuvable") {
      throw new CrecheLookupError("Ce code ne correspond à aucune crèche. Vérifiez-le sur la fiche de mise en service.");
    }
    throw error;
  }
}

export type Compat = { api: number; minimum: Record<string, string>; creche: string | null; code: string | null };

/** L'instance répond-elle, et comment s'appelle-t-elle ? */
export async function checkInstance(adresse: string, signal?: AbortSignal): Promise<Compat> {
  try {
    return await getJson<Compat>(`${adresse}/api/v1/app/compat`, signal);
  } catch (error) {
    if (error instanceof CrecheLookupError && error.message === "introuvable") {
      throw new CrecheLookupError("Cette adresse n’est pas celle d’une crèche Ti’Cagou.");
    }
    throw error;
  }
}
