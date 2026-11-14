/**
 * Verrouillage de l’application Ti’Cagou Parents (maquette Pa9, ADR 0024) : proposé, désactivé par défaut. Activé dans
 * « Mon compte », il demande Face ID, l’empreinte ou le code du téléphone à l’ouverture et après 2 minutes en arrière-plan.
 */
import { useEffect, useRef, useState, type ReactNode } from "react";
import { Fingerprint } from "lucide-react";
import { Logo, TouchButton } from "./components";
import { IS_APP, hasPlugins, preferences } from "./native";
import { LOCK_KEY } from "./screens/ComptePage";

const LOCK_AFTER_MS = 2 * 60_000;

async function unlockWithDevice(): Promise<boolean> {
  if (!hasPlugins()) return true;
  const { BiometricAuth } = await import("@aparajita/capacitor-biometric-auth");
  try {
    await BiometricAuth.authenticate({ reason: "Déverrouiller Ti’Cagou", cancelTitle: "Annuler", allowDeviceCredential: true,
      iosFallbackTitle: "Utiliser le code", androidTitle: "Ti’Cagou" });
    return true;
  } catch {
    return false;
  }
}

export function AppLock({ email, children }: { email: string; children: ReactNode }) {
  const [enabled, setEnabled] = useState(false);
  const [locked, setLocked] = useState(false);
  const hiddenAt = useRef<number | null>(null);

  useEffect(() => {
    if (!IS_APP) return;
    void preferences.get(LOCK_KEY).then((value) => {
      const on = value === "1";
      setEnabled(on);
      setLocked(on);
      if (on && hasPlugins()) void unlockWithDevice().then((ok) => ok && setLocked(false));
    });
  }, []);

  useEffect(() => {
    if (!IS_APP || !enabled || !hasPlugins()) return;
    let remove = () => {};
    void import("@capacitor/app").then(({ App }) =>
      App.addListener("appStateChange", ({ isActive }) => {
        if (!isActive) {
          hiddenAt.current = Date.now();
        } else if (hiddenAt.current !== null && Date.now() - hiddenAt.current > LOCK_AFTER_MS) {
          setLocked(true);
          void unlockWithDevice().then((ok) => ok && setLocked(false));
        }
      }).then((handle) => (remove = () => void handle.remove())));
    return () => remove();
  }, [enabled]);

  if (!locked) return <>{children}</>;
  return (
    <main className="mx-auto flex min-h-dvh max-w-[480px] flex-col items-center gap-4 px-6 pt-[22vh] pb-8 text-center">
      <Logo size={48} />
      <p className="m-0 text-ink-muted">{email}</p>
      <Fingerprint aria-hidden className="mt-10 size-12 text-lagon-700" />
      <p className="m-0 text-[18px] font-semibold">Application verrouillée</p>
      <div className="grow" />
      <TouchButton onClick={() => void unlockWithDevice().then((ok) => ok && setLocked(false))}>Déverrouiller</TouchButton>
    </main>
  );
}
