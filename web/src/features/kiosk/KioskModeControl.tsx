/**
 * Mode kiosque de l’application Ti’Cagou Pointage (maquettes P3 à P5) : activé par la directrice, indiqué dans la barre,
 * quitté par un appui long de 5 secondes puis le code personnel d’une personne de la direction, vérifié par le serveur
 * (connexion par PIN, tracée au journal).
 */
import { useEffect, useRef, useState } from "react";
import { Pin } from "lucide-react";
import { api, ApiError, errorMessage } from "@/lib/api";
import { disableKioskMode, enableKioskMode, kioskModeAvailable, kioskStatus, type KioskState } from "@/lib/native/kiosk";
import { cn } from "@/lib/utils";
import { useKiosk } from "./KioskContext";
import { PinPad } from "./PinPad";
import { TouchButton } from "./components";

const LONG_PRESS_MS = 5_000;

export function KioskModeControl() {
  const { device, today, online } = useKiosk();
  const [state, setState] = useState<KioskState | null>(null);
  const [exiting, setExiting] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (kioskModeAvailable()) void kioskStatus().then(setState).catch(() => {});
  }, []);

  if (!kioskModeAvailable() || !device || !state) return null;

  if (!state.enabled) {
    return (
      <button type="button" onClick={() => void enableKioskMode().then(setState)}
        className="flex h-12 items-center gap-2 rounded-full border border-lagon-600 px-4 text-base font-semibold text-lagon-700">
        <Pin aria-hidden className="size-5" />
        Activer le mode kiosque
      </button>
    );
  }

  const press = () => {
    timer.current = setTimeout(() => setExiting(true), LONG_PRESS_MS);
  };
  const release = () => {
    if (timer.current) clearTimeout(timer.current);
  };

  return (
    <>
      <button type="button" onPointerDown={press} onPointerUp={release} onPointerLeave={release}
        aria-label="Mode kiosque actif (appui long de 5 secondes pour en sortir)"
        className="flex h-12 select-none items-center gap-2 rounded-full bg-lagon-100 px-4 text-base font-semibold text-lagon-700">
        <Pin aria-hidden className="size-5" />
        Mode kiosque
      </button>
      {exiting && (
        <ExitDialog directors={today?.directors ?? []} online={online} onCancel={() => setExiting(false)}
          onExit={async () => {
            setState(await disableKioskMode());
            setExiting(false);
          }} />
      )}
    </>
  );
}

function ExitDialog({ directors, online, onCancel, onExit }: {
  directors: { userId: string; displayName: string }[];
  online: boolean;
  onCancel: () => void;
  onExit: () => Promise<void>;
}) {
  const [director, setDirector] = useState(directors[0] ?? null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  const submit = async (pin: string) => {
    if (!director) return;
    setError(null);
    setPending(true);
    try {
      // Vérifié par le serveur : un code faux compte comme un échec de connexion (blocage après 5 essais).
      await api("/auth/pin", { method: "POST", auth: "device", body: { userId: director.userId, pin } });
      await onExit();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : errorMessage(e));
    } finally {
      setPending(false);
    }
  };

  return (
    <div role="dialog" aria-modal="true" aria-label="Sortir du mode kiosque" className="fixed inset-0 z-50 grid place-items-center bg-overlay">
      <div className="flex w-full max-w-lg flex-col items-center gap-4 rounded-lg bg-card p-6 text-center">
        <h2 className="m-0 text-touch-title">Sortir du mode kiosque</h2>
        {!online ? (
          <p className="m-0 text-touch-body text-muted-foreground">Hors connexion : le code de la direction ne peut pas être vérifié. Réessayez au retour du réseau.</p>
        ) : directors.length === 0 ? (
          <p className="m-0 text-touch-body text-muted-foreground">Aucune personne de la direction n’a de code PIN.</p>
        ) : (
          <>
            <p className="m-0 text-touch-body text-muted-foreground">Code personnel d’une personne de la direction. La sortie est tracée au journal.</p>
            {directors.length > 1 && (
              <div className="flex flex-wrap justify-center gap-2">
                {directors.map((d) => (
                  <button key={d.userId} type="button" aria-pressed={director?.userId === d.userId} onClick={() => setDirector(d)}
                    className={cn("h-touch rounded-full border px-4 text-touch-label", director?.userId === d.userId ? "border-lagon-600 bg-lagon-100 text-lagon-700" : "border-border")}>
                    {d.displayName}
                  </button>
                ))}
              </div>
            )}
            <PinPad label={`Code personnel de ${director?.displayName}`} onComplete={(pin) => void submit(pin)} error={error} pending={pending} />
          </>
        )}
        <TouchButton variant="secondary" onClick={onCancel}>Annuler</TouchButton>
      </div>
    </div>
  );
}
