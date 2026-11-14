import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router";
import { QrCode, TabletSmartphone } from "lucide-react";
import { ApiError, errorMessage } from "@/lib/api";
import { loadCurrentCreche } from "@/lib/native/bootstrap";
import { chooseCreche, currentCreche, normalizeAddress } from "@/lib/native/creches";
import { scanQr } from "@/lib/native/device";
import { hasNativePlugins, isNativeApp } from "@/lib/native/platform";
import { pairDevice } from "./device";
import { PinPad } from "./PinPad";
import { TouchButton } from "./components";

/** QR d’appairage affiché par le PC du bureau : « https://<crèche>/kiosk?pair=CODE » (ADR 0008). */
export function parsePairingQr(text: string): { adresse: string; code: string } | null {
  try {
    const url = new URL(text.trim());
    const code = url.searchParams.get("pair");
    const adresse = normalizeAddress(url.origin);
    if (!code || !/^\d{6}$/.test(code) || !adresse || url.pathname !== "/kiosk") return null;
    return { adresse, code };
  } catch {
    return null;
  }
}

/**
 * Première ouverture de la tablette : appairage par le QR code affiché sur le PC du bureau (« /kiosk?pair=CODE »,
 * ADR 0008) ou saisie du code (ADR 0005). Dans l’application Ti’Cagou Pointage (maquettes P1 et P2), le QR donne aussi
 * l’adresse de la crèche ; à la main, on la tape avant le code.
 */
export function PairingPage() {
  const [params, setParams] = useSearchParams();
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [address, setAddress] = useState("");
  const scanned = useRef(false);
  const native = isNativeApp();
  const needsAddress = native && currentCreche() === null;

  /** Application : la crèche du QR ou de l’adresse tapée devient celle de la tablette, puis on appaire. */
  const pairAt = async (adresse: string | null, code: string) => {
    if (adresse) {
      await chooseCreche({ nom: new URL(adresse).host, commune: null, adresse, code: null });
      await loadCurrentCreche();
    }
    const paired = await pairDevice(code);
    if (adresse) await chooseCreche({ nom: paired.structureName, commune: null, adresse, code: null });
  };

  const submit = async (code: string, adresse: string | null = null) => {
    setError(null);
    let target = adresse;
    if (!target && needsAddress) {
      target = normalizeAddress(address);
      if (!target) {
        setError("Tapez d’abord l’adresse de la crèche (https://…), puis le code.");
        return;
      }
    }
    setPending(true);
    try {
      await pairAt(target, code);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : errorMessage(e));
    } finally {
      setPending(false);
    }
  };

  const onScan = async () => {
    const text = await scanQr("Visez le QR affiché sur l’ordinateur de la crèche");
    if (!text) return;
    const qr = parsePairingQr(text);
    if (!qr) {
      setError("Ce QR code n’est pas un QR d’appairage Ti’Cagou (Paramètres › Postes › Ajouter une tablette).");
      return;
    }
    await submit(qr.code, qr.adresse);
  };

  useEffect(() => {
    const code = params.get("pair");
    if (code && !scanned.current) {
      scanned.current = true;
      // Le code ne reste pas dans l'adresse : il ne sert qu'une fois.
      setParams({}, { replace: true });
      void submit(code);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
      {native && hasNativePlugins() && (
        <TouchButton className="w-full" onClick={() => void onScan()} disabled={pending}>
          <QrCode aria-hidden className="size-6" />
          Scanner le QR
        </TouchButton>
      )}
      {needsAddress && (
        <label className="flex w-full flex-col gap-2 text-left text-touch-label font-normal">
          Adresse de la crèche
          <input
            value={address}
            onChange={(e) => setAddress(e.target.value)}
            inputMode="url"
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
            placeholder="https://ma-creche.ticagou.nc"
            className="h-touch rounded-md border border-input bg-card px-4 text-touch-body"
          />
        </label>
      )}
      {pending && <p className="m-0 text-touch-body">Appairage en cours…</p>}
      <PinPad length={6} label="Code d’appairage" onComplete={(code) => void submit(code)} error={error} pending={pending} />
    </div>
  );
}
