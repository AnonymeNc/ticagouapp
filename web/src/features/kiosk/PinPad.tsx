import { useEffect, useState } from "react";
import { Delete } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * Pavé numérique tactile (maquette Pointage tablette) : les chiffres saisis restent masqués, la saisie
 * part d'elle-même au dernier chiffre. Une erreur vide le code.
 */
export function PinPad({
  length = 4,
  onComplete,
  error,
  pending = false,
  label,
  cancel,
}: {
  length?: number;
  onComplete: (code: string) => Promise<unknown> | void;
  error?: string | null;
  pending?: boolean;
  /** Nom accessible du pavé (« Code personnel de Mme Hnawia »). */
  label: string;
  cancel?: { label: string; onClick: () => void };
}) {
  const [code, setCode] = useState("");

  useEffect(() => {
    if (code.length !== length) return;
    const result = onComplete(code);
    if (result instanceof Promise) result.finally(() => setCode(""));
    else setCode("");
    // onComplete change à chaque rendu du parent : seule la saisie complète déclenche l'envoi.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [code, length]);

  const press = (digit: string) => setCode((current) => (current.length < length ? current + digit : current));
  const key =
    "flex h-touch-lg items-center justify-center rounded-lg border border-border bg-card text-touch-title shadow-1 active:border-lagon-600 active:bg-lagon-100 disabled:opacity-50";

  return (
    <div role="group" aria-label={label} className="flex w-full max-w-sm flex-col items-center gap-4">
      <div className="flex gap-4" aria-label={`${code.length} chiffre(s) saisi(s) sur ${length}`} role="status">
        {Array.from({ length }, (_, i) => (
          <span
            key={i}
            className={cn(
              "size-5 rounded-full border-2",
              error ? "border-danger-700" : "border-lagon-600",
              i < code.length && "border-lagon-600 bg-lagon-600",
            )}
          />
        ))}
      </div>
      <p role="alert" className="m-0 min-h-7 text-center text-touch-label text-danger-700">
        {error}
      </p>
      <div className="grid w-full grid-cols-3 gap-3">
        {["1", "2", "3", "4", "5", "6", "7", "8", "9"].map((d) => (
          <button key={d} type="button" className={key} onClick={() => press(d)} disabled={pending}>
            {d}
          </button>
        ))}
        {cancel ? (
          <button type="button" className={cn(key, "bg-muted text-touch-label text-muted-foreground")} onClick={cancel.onClick}>
            {cancel.label}
          </button>
        ) : (
          <span />
        )}
        <button type="button" className={key} onClick={() => press("0")} disabled={pending}>
          0
        </button>
        <button
          type="button"
          className={cn(key, "bg-muted text-muted-foreground")}
          aria-label="Effacer"
          onClick={() => setCode((current) => current.slice(0, -1))}
          disabled={pending}
        >
          <Delete aria-hidden className="size-8" />
        </button>
      </div>
    </div>
  );
}
