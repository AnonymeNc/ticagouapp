/**
 * Taux d'encadrement calculé sur la tablette, à partir des ratios fournis par le serveur pour chaque enfant
 * (même règle que RatioCalculator côté serveur) : il reste juste hors connexion.
 */
export type RatioStatus = "CONFORME" | "LIMITE" | "INSUFFISANT";

export type Load = { children: number; adults: number; required: number; coveredPlaces: number; status: RatioStatus };

const gcd = (a: number, b: number): number => (b === 0 ? a : gcd(b, a % b));
const lcm = (a: number, b: number) => (a / gcd(a, b)) * b;

/**
 * @param ratios        enfants par adulte de chaque enfant présent
 * @param fallbackRatio ratio des places couvertes quand aucun enfant n'est présent
 */
export function load(ratios: number[], adults: number, fallbackRatio: number): Load {
  const children = ratios.length;
  const strictest = children > 0 ? Math.min(...ratios) : fallbackRatio;
  const common = ratios.reduce(lcm, strictest);
  const shares = ratios.reduce((sum, n) => sum + common / n, 0);
  const capacity = adults * common;
  const required = Math.ceil(shares / common);
  const covered = children + Math.floor((capacity - shares) / (common / strictest));
  const status: RatioStatus =
    capacity < shares ? "INSUFFISANT" : children > 0 && covered - children <= 1 ? "LIMITE" : "CONFORME";
  return { children, adults, required, coveredPlaces: Math.max(covered, 0), status };
}

export const statusLabels: Record<RatioStatus, string> = {
  CONFORME: "Encadrement conforme",
  LIMITE: "Encadrement à la limite",
  INSUFFISANT: "Encadrement insuffisant",
};

export const statusShort: Record<RatioStatus, string> = {
  CONFORME: "Conforme",
  LIMITE: "À la limite",
  INSUFFISANT: "Insuffisant",
};

export function adultsLabel(load: Load) {
  return `${load.adults} adulte${load.adults > 1 ? "s" : ""} · ${load.required} requis`;
}
