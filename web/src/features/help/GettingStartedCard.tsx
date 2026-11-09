import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { CircleCheck, Circle, Rocket } from "lucide-react";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";

type Step = { key: string; label: string; done: boolean; path: string };

const HIDDEN = "ticagou.premiers-pas.masque";

function hidden() {
  try {
    return window.localStorage.getItem(HIDDEN) === "1";
  } catch {
    return false;
  }
}

/**
 * Tableau de bord de la direction (lot 9) : ce qui reste à mettre en place, d'après l'état réel de la crèche. La carte
 * disparaît quand tout est fait, ou si la direction la masque (ce poste seulement).
 */
export function GettingStartedCard() {
  const [masked, setMasked] = useState(hidden);
  const steps = useQuery({ queryKey: ["getting-started"], queryFn: ({ signal }) => api<Step[]>("/getting-started", { signal }), enabled: !masked });
  const list = steps.data ?? [];
  const left = list.filter((s) => !s.done).length;
  if (masked || list.length === 0 || left === 0) return null;

  return (
    <section aria-labelledby="premiers-pas" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div className="flex items-center gap-2">
        <h2 id="premiers-pas" className="m-0 flex grow items-center gap-2 text-title-2"><Rocket aria-hidden className="size-5 text-lagon-700" />Premiers pas</h2>
        <span className="text-small text-muted-foreground">{list.length - left} sur {list.length}</span>
      </div>
      <ul className="m-0 flex list-none flex-col gap-1 p-0">
        {list.map((s) => (
          <li key={s.key} className="flex items-center gap-2">
            {s.done
              ? <CircleCheck aria-label="Fait" className="size-5 shrink-0 text-success-700" />
              : <Circle aria-label="À faire" className="size-5 shrink-0 text-muted-foreground" />}
            {s.done ? <span className="text-muted-foreground line-through">{s.label}</span> : <Link to={s.path}>{s.label}</Link>}
          </li>
        ))}
      </ul>
      <Button variant="ghost" size="sm" className="self-start" onClick={() => {
        try {
          window.localStorage.setItem(HIDDEN, "1");
        } catch {
          // Stockage indisponible : la carte reviendra au prochain chargement.
        }
        setMasked(true);
      }}>Masquer cette liste</Button>
    </section>
  );
}
