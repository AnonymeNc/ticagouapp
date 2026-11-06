import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { WifiOff } from "lucide-react";
import { roleLabels, type PinUser } from "@/features/auth/types";
import { ApiError, errorMessage } from "@/lib/api";
import { kioskApi } from "./api";
import { useKiosk } from "./KioskContext";
import { PinPad } from "./PinPad";

/**
 * Écran de la tablette sans session : choix de la personne puis code PIN. Hors connexion, la liste vient de
 * l'instantané du jour et le code sera vérifié au retour du serveur (ADR 0005).
 */
export function PinLogin() {
  const { online, today } = useKiosk();
  const [selected, setSelected] = useState<PinUser | null>(null);
  const users = useQuery({
    queryKey: ["pin-users"],
    queryFn: ({ signal }) => kioskApi.pinUsers(signal),
    retry: false,
  });

  const offlineUsers: PinUser[] = (today?.staff ?? [])
    .filter((s) => s.hasPin)
    .map((s) => ({ id: s.userId, displayName: shortName(s.displayName), initials: s.initials, role: s.role }));
  const list = users.data ?? (online ? undefined : offlineUsers);

  if (selected) return <PinStep user={selected} onCancel={() => setSelected(null)} />;

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6 pt-8">
      <h1 className="m-0 text-touch-title">Qui êtes-vous ?</h1>
      {!online && (
        <p className="m-0 flex items-center gap-2 text-touch-body text-muted-foreground">
          <WifiOff aria-hidden className="size-6" />
          Hors connexion : votre code sera vérifié au retour du serveur.
        </p>
      )}
      {users.isPending && online && <p className="text-touch-body text-muted-foreground">Chargement…</p>}
      {users.isError && online && <p className="text-touch-body text-muted-foreground">{errorMessage(users.error)}</p>}
      {list?.length === 0 && (
        <p className="text-touch-body text-muted-foreground">
          Personne n’a encore de code PIN. La direction peut en définir dans Paramètres › Utilisateurs et rôles.
        </p>
      )}
      <ul className="m-0 grid list-none grid-cols-2 gap-4 p-0 md:grid-cols-3 lg:grid-cols-4">
        {list?.map((user) => (
          <li key={user.id}>
            <button
              type="button"
              onClick={() => setSelected(user)}
              className="flex h-24 w-full items-center gap-4 rounded-lg border border-border bg-card px-5 text-left shadow-1 active:bg-lagon-50"
            >
              <span aria-hidden className="flex size-14 shrink-0 items-center justify-center rounded-full bg-lagon-100 text-touch-label font-bold text-lagon-700">
                {user.initials}
              </span>
              <span className="flex flex-col">
                <span className="text-touch-label">{user.displayName}</span>
                <span className="text-base text-muted-foreground">{roleLabels[user.role]}</span>
              </span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

function shortName(name: string) {
  const [first, ...rest] = name.split(" ");
  return rest.length ? `${first} ${rest.join(" ").charAt(0)}.` : first;
}

function PinStep({ user, onCancel }: { user: PinUser; onCancel: () => void }) {
  const { online, today, loginOnline, loginOffline } = useKiosk();
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  const submit = async (pin: string) => {
    setError(null);
    setPending(true);
    try {
      if (online) {
        await loginOnline(user.id, pin);
      } else {
        const staff = today?.staff.find((s) => s.userId === user.id);
        if (staff) await loginOffline(staff, pin);
      }
    } catch (e) {
      if (e instanceof ApiError && e.code === "bad_pin") setError("Code incorrect, réessayez.");
      else if (!(e instanceof ApiError) && today) {
        // Le serveur vient de tomber : connexion hors ligne, code vérifié plus tard.
        const staff = today.staff.find((s) => s.userId === user.id);
        if (staff) await loginOffline(staff, pin);
      } else setError(errorMessage(e));
    } finally {
      setPending(false);
    }
  };

  return (
    <div className="mx-auto flex max-w-sm flex-col items-center gap-6 pt-6">
      <div className="text-center">
        <h1 className="m-0 text-touch-title">Bonjour {user.displayName.split(" ")[0]}</h1>
        <p className="m-0 mt-1 text-touch-body text-muted-foreground">Tapez votre code PIN</p>
      </div>
      <PinPad
        label={`Code PIN de ${user.displayName}`}
        onComplete={submit}
        error={error}
        pending={pending}
        cancel={{ label: "Retour", onClick: onCancel }}
      />
    </div>
  );
}
