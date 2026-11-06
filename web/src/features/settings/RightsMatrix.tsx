import { TonePill, type Tone } from "@/features/enrollment/components";
import { useModules, type LicenseModule } from "@/features/license/useModules";

/** Droits appliqués par le serveur, par module et par rôle (maquette « Droits par rôle »). */
const RIGHTS: [string, string, string, string, string, LicenseModule?][] = [
  ["Présences et pointage", "Modifier", "Modifier", "Consulter", "Consulter"],
  ["Enfants, santé et PAI", "Modifier", "Consulter", "Allergies et régimes", "Aucun accès"],
  ["Inscriptions et contrats", "Modifier", "Aucun accès", "Aucun accès", "Consulter", "INSCRIPTIONS"],
  ["Facturation et paiements", "Modifier", "Aucun accès", "Aucun accès", "Modifier", "FACTURATION"],
  ["Repas et menus", "Modifier", "Consulter", "Modifier", "Aucun accès", "REPAS"],
  ["Personnel et plannings", "Modifier", "Son planning", "Son planning", "Consulter", "PERSONNEL"],
  ["Paramètres", "Modifier", "Aucun accès", "Aucun accès", "Barème et financeurs"],
  ["Sauvegardes", "Modifier", "Aucun accès", "Aucun accès", "Aucun accès"],
];

const tone = (right: string): Tone => (right === "Modifier" ? "success" : right === "Aucun accès" ? "neutral" : "info");

export function RightsMatrix() {
  const modules = useModules();
  return (
    <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div>
        <h2 className="m-0 text-title-3">Droits par rôle</h2>
        <p className="m-0 text-muted-foreground">Ce que chaque rôle peut faire, module par module. Ces droits sont fixés ; leur personnalisation viendra plus tard.</p>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-left">
          <caption className="sr-only">Droits par rôle</caption>
          <thead>
            <tr className="border-b border-border-subtle text-label text-muted-foreground">
              {["Module", "Direction", "Éducatrice", "Cuisine", "Comptabilité"].map((h) => <th key={h} scope="col" className="py-2 pr-3">{h}</th>)}
            </tr>
          </thead>
          <tbody>
            {RIGHTS.filter(([, , , , , required]) => !required || modules.has(required)).map(([name, ...cells]) => (
              <tr key={name} className="border-b border-border-subtle last:border-0">
                <th scope="row" className="py-2 pr-3 text-left font-normal">{name}</th>
                {cells.slice(0, 4).map((c, i) => <td key={i} className="py-2 pr-3"><TonePill tone={tone(c as string)}>{c}</TonePill></td>)}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
