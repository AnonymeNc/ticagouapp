/**
 * Verrouillage de l’application Direction (ADR 0024, maquettes A6 et A7) : Face ID, empreinte ou code du téléphone à
 * chaque ouverture et après 2 minutes en arrière-plan. Sans verrou sur le téléphone, la session n’est pas gardée :
 * l’application se déconnecte dès qu’elle passe en arrière-plan. Le verrou est local : il ne remplace pas la double
 * authentification et le serveur ne le voit pas.
 */
import { useEffect, useRef, useState, type ReactNode } from "react";
import { Fingerprint, LogOut } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useAuth } from "@/features/auth/AuthProvider";
import { sessionRestoredFromVault } from "@/features/auth/tokens";
import { currentCreche } from "@/lib/native/creches";
import { lockSupport, onAppStateChange, unlockWithDevice, type LockSupport } from "@/lib/native/device";
import { hasNativePlugins, isNativeApp } from "@/lib/native/platform";

/** Délai en arrière-plan au-delà duquel l’application se verrouille. */
export const LOCK_AFTER_MS = 2 * 60_000;

export function AppLock({ children }: { children: ReactNode }) {
  const native = isNativeApp();
  const { state, logout } = useAuth();
  // Session rouverte depuis le coffre : verrouillée. Juste après une connexion par mot de passe : non.
  const [locked, setLocked] = useState(() => native && sessionRestoredFromVault());
  const [support, setSupport] = useState<LockSupport | null>(null);
  const hiddenAt = useRef<number | null>(null);
  const userName = state.status === "authenticated" ? `${state.user.firstName} ${state.user.lastName}` : "";

  useEffect(() => {
    if (!native) return;
    void lockSupport().then(setSupport, () => setSupport({ biometry: false, secure: false }));
  }, [native]);

  const unlock = async () => {
    if (await unlockWithDevice("Déverrouiller Ti’Cagou")) setLocked(false);
  };

  // Première ouverture : la demande de Face ID / empreinte part d’elle-même.
  useEffect(() => {
    if (native && locked && support?.secure && hasNativePlugins()) void unlock();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [native, support]);

  useEffect(() => {
    if (!native) return;
    let stop = () => {};
    void onAppStateChange((active) => {
      if (!active) {
        hiddenAt.current = Date.now();
        // Téléphone sans verrou : la session ne survit pas à l’arrière-plan.
        if (support && !support.secure) void logout();
        return;
      }
      if (hiddenAt.current !== null && Date.now() - hiddenAt.current > LOCK_AFTER_MS) setLocked(true);
      hiddenAt.current = null;
    }).then((remove) => (stop = remove));
    return () => stop();
  }, [native, support, logout]);

  if (!native || !locked || (support && !support.secure)) {
    return (
      <>
        {native && support && !support.secure && (
          <p role="note" className="m-0 bg-warning-100 px-4 py-2 text-small text-warning-700">
            Ce téléphone n’a pas de code de verrouillage : Ti’Cagou vous déconnecte dès que vous quittez l’application.
          </p>
        )}
        {children}
      </>
    );
  }
  return (
    <div className="flex min-h-dvh flex-col items-center gap-4 bg-background px-6 pt-[20vh] pb-10 text-center">
      <span className="font-display text-display text-lagon-700">Ti’Cagou</span>
      <p className="m-0 text-muted-foreground">{[currentCreche()?.nom, userName].filter(Boolean).join(" · ")}</p>
      <Fingerprint aria-hidden className="mt-10 size-12 text-lagon-700" />
      <p className="m-0 font-semibold">Application verrouillée</p>
      <div className="mt-auto flex w-full max-w-sm flex-col gap-2">
        <Button className="h-12" onClick={() => void unlock()}>Déverrouiller</Button>
        <Button variant="ghost" onClick={() => void logout()}><LogOut aria-hidden />Se déconnecter</Button>
      </div>
    </div>
  );
}
