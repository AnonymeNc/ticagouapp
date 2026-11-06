import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router";
import { TabletSmartphone } from "lucide-react";
import { ApiError, errorMessage } from "@/lib/api";
import { pairDevice } from "./device";
import { PinPad } from "./PinPad";

/**
 * Première ouverture de la tablette : appairage par le QR code affiché sur le PC du bureau (« /kiosk?pair=CODE »,
 * ADR 0008) ou saisie du code (ADR 0005).
 */
export function PairingPage() {
  const [params, setParams] = useSearchParams();
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const scanned = useRef(false);

  const submit = async (code: string) => {
    setError(null);
    setPending(true);
    try {
      await pairDevice(code);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : errorMessage(e));
    } finally {
      setPending(false);
    }
  };

  useEffect(() => {
    const code = params.get("pair");
    if (code && !scanned.current) {
      scanned.current = true;
      // Le code ne reste pas dans l'adresse : il ne sert qu'une fois.
      setParams({}, { replace: true });
      void submit(code);
    }
  }, [params, setParams]);

  return (
    <div className="mx-auto flex max-w-xl flex-col items-center gap-6 pt-8 text-center">
      <TabletSmartphone aria-hidden className="size-14 text-lagon-600" strokeWidth={1.5} />
      <div>
        <h1 className="m-0 text-touch-title">Appairer cette tablette</h1>
        <p className="m-0 mt-2 text-touch-body text-muted-foreground">
          Sur le PC du bureau, ouvrez Paramètres › Tablettes et abonnement puis « Appairer une tablette ».
          Scannez le QR code avec l’appareil photo, ou tapez ici le code à 6 chiffres affiché.
        </p>
      </div>
      {pending && <p className="m-0 text-touch-body">Appairage en cours…</p>}
      <PinPad length={6} label="Code d’appairage" onComplete={submit} error={error} pending={pending} />
    </div>
  );
}
