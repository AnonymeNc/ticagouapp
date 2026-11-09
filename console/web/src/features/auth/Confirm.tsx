import { createContext, useCallback, useContext, useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api, ApiError, errorMessage } from "@/lib/api";

type Run = <T>(action: () => Promise<T>) => Promise<T>;

const ConfirmContext = createContext<Run | null>(null);

type Pending = { action: () => Promise<unknown>; resolve: (value: unknown) => void; reject: (error: unknown) => void };

/**
 * Actions sensibles (valider une facture, émettre un avoir) : si le serveur répond « confirmation_required », on
 * demande le code de l'application d'authentification puis on rejoue l'action (ADR 0017, valable 5 minutes).
 */
export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<Pending | null>(null);

  const run = useCallback<Run>(async (action) => {
    try {
      return await action();
    } catch (error) {
      if (error instanceof ApiError && error.status === 403 && error.code === "confirmation_required") {
        return new Promise((resolve, reject) => setPending({ action, resolve: resolve as (v: unknown) => void, reject }));
      }
      throw error;
    }
  }, []);

  return (
    <ConfirmContext.Provider value={run}>
      {children}
      {pending && (
        <CodeDialog
          onCancel={() => {
            pending.reject(new ApiError(0, "annule", "Action annulée : le code n’a pas été saisi."));
            setPending(null);
          }}
          onConfirmed={async () => {
            try {
              pending.resolve(await pending.action());
            } catch (error) {
              pending.reject(error);
            } finally {
              setPending(null);
            }
          }}
        />
      )}
    </ConfirmContext.Provider>
  );
}

export function useConfirmed(): Run {
  const run = useContext(ConfirmContext);
  if (!run) {
    throw new Error("useConfirmed hors de ConfirmProvider");
  }
  return run;
}

function CodeDialog({ onCancel, onConfirmed }: { onCancel: () => void; onConfirmed: () => Promise<void> }) {
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => input.current?.focus(), []);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api<void>("/auth/confirm", { method: "POST", body: { code: code.replace(/\D/g, "") } });
      await onConfirmed();
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-overlay p-4" onKeyDown={(e) => e.key === "Escape" && onCancel()}>
      <form
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirm-title"
        onSubmit={submit}
        className="flex w-full max-w-sm flex-col gap-4 rounded-lg bg-card p-6 shadow-3"
      >
        <div>
          <h2 id="confirm-title" className="m-0 font-display text-title-2">
            Confirmez avec votre code
          </h2>
          <p className="m-0 mt-1 text-small text-muted-foreground">
            Action sensible : saisissez le code de votre application d’authentification. Il vaut pour 5 minutes.
          </p>
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="confirm-code">Code à 6 chiffres</Label>
          <Input
            id="confirm-code"
            ref={input}
            inputMode="numeric"
            autoComplete="one-time-code"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            maxLength={7}
          />
        </div>
        {error && (
          <p role="alert" className="m-0 text-danger-700">
            {error}
          </p>
        )}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onCancel}>
            Annuler
          </Button>
          <Button type="submit" disabled={busy || code.replace(/\D/g, "").length !== 6}>
            Confirmer
          </Button>
        </div>
      </form>
    </div>
  );
}
