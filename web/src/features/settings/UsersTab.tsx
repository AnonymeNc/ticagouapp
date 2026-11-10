import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { KeyRound, Plus } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { roleLabels, type Role } from "@/features/auth/types";
import { TonePill } from "@/features/enrollment/components";
import { useSections } from "@/features/structure/useSections";
import { errorMessage } from "@/lib/api";
import { selectClass, textOrNull } from "@/lib/formFields";
import { usersApi, type UserRow } from "./api";

const ROLES: Role[] = ["DIRECTION", "EDUCATRICE", "CUISINE", "COMPTABILITE"];

/**
 * Paramètres › Utilisateurs et rôles : comptes du personnel, code PIN des tablettes, section habituelle et
 * prise en compte dans le taux d'encadrement. La fiche Personnel complète arrivera avec sa phase.
 */
export function UsersTab() {
  const queryClient = useQueryClient();
  const users = useQuery({ queryKey: ["users"], queryFn: ({ signal }) => usersApi.list(signal) });
  const sections = useSections();
  const [editing, setEditing] = useState<UserRow | "new" | null>(null);
  const [pinFor, setPinFor] = useState<UserRow | null>(null);
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["users"] });
  const resetMfa = async (user: UserRow) => {
    if (!window.confirm(`Réinitialiser la double authentification de ${user.firstName} ${user.lastName} ? Cette personne enregistrera un nouveau téléphone à sa prochaine connexion.`)) return;
    await usersApi.resetMfa(user.id);
  };
  const sectionName = (id: string | null) => sections.data?.find((s) => s.id === id)?.name;
  const counted = users.data?.filter((u) => u.active && u.countsInRatio).length ?? 0;

  return (
    <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="m-0 text-title-3">Utilisateurs et rôles</h2>
          <p className="m-0 text-muted-foreground">
            Mot de passe sur le PC du bureau, code PIN à 4 chiffres sur les tablettes. {users.data && <>{counted} personne{counted > 1 ? "s comptent" : " compte"} dans le taux d’encadrement.</>}
          </p>
        </div>
        <Button onClick={() => setEditing("new")}>
          <Plus aria-hidden />
          Ajouter une personne
        </Button>
      </div>
      {users.isError && <p className="text-danger-700">{errorMessage(users.error)}</p>}
      <table className="w-full border-collapse text-left">
        <caption className="sr-only">Utilisateurs</caption>
        <thead>
          <tr className="border-b border-border-subtle text-label text-muted-foreground">
            <th scope="col" className="py-2">Nom</th>
            <th scope="col" className="py-2">Rôle</th>
            <th scope="col" className="py-2">Connexion</th>
            <th scope="col" className="py-2">Section habituelle</th>
            <th scope="col" className="py-2">Encadrement</th>
            <th scope="col" className="py-2">Statut</th>
            <th scope="col" className="py-2"><span className="sr-only">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          {users.data?.map((u) => (
            <tr key={u.id} className="border-b border-border-subtle last:border-0">
              <td className="py-2">
                {u.firstName} {u.lastName}
                {u.jobTitle && <span className="block text-small text-muted-foreground">{u.jobTitle}</span>}
              </td>
              <td className="py-2">{roleLabels[u.role]}</td>
              <td className="py-2 text-small">
                {[u.hasPassword && "Mot de passe · PC du bureau", u.hasPin && "Code PIN · tablettes"].filter(Boolean).join(" ; ") || "—"}
              </td>
              <td className="py-2">{sectionName(u.homeSectionId) ?? "Toutes sections"}</td>
              <td className="py-2">{u.countsInRatio ? "Compte dans le taux" : "Hors encadrement"}</td>
              <td className="py-2">
                {u.locked ? <TonePill tone="warning">Bloqué</TonePill> : u.active ? <TonePill tone="success">Actif</TonePill> : <TonePill tone="neutral">Désactivé</TonePill>}
              </td>
              <td className="py-2 text-right whitespace-nowrap">
                <Button variant="ghost" size="sm" onClick={() => setPinFor(u)} aria-label={`Code PIN de ${u.firstName} ${u.lastName}`}>
                  <KeyRound aria-hidden />
                  PIN
                </Button>
                <Button variant="ghost" size="sm" onClick={() => setEditing(u)}>Modifier</Button>
                {(u.role === "DIRECTION" || u.role === "COMPTABILITE") && (
                  <Button variant="ghost" size="sm" onClick={() => void resetMfa(u)}>Téléphone perdu</Button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {editing && <UserDialog user={editing === "new" ? null : editing} onClose={() => setEditing(null)} onDone={refresh} />}
      {pinFor && <PinDialog user={pinFor} onClose={() => setPinFor(null)} onDone={refresh} />}
    </section>
  );
}

function UserDialog({ user, onClose, onDone }: { user: UserRow | null; onClose: () => void; onDone: () => Promise<unknown> }) {
  const sections = useSections();
  const roles = ROLES;
  const [firstName, setFirstName] = useState(user?.firstName ?? "");
  const [lastName, setLastName] = useState(user?.lastName ?? "");
  const [role, setRole] = useState<Role>(user?.role ?? "EDUCATRICE");
  const [jobTitle, setJobTitle] = useState(user?.jobTitle ?? "");
  const [homeSectionId, setHomeSectionId] = useState(user?.homeSectionId ?? "");
  const [countsInRatio, setCountsInRatio] = useState(user?.countsInRatio ?? true);
  const [login, setLogin] = useState(user?.login ?? "");
  const [password, setPassword] = useState("");
  const [pin, setPin] = useState("");
  const [active, setActive] = useState(user?.active ?? true);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    if (!firstName.trim() || !lastName.trim()) return setError("Indiquez le prénom et le nom.");
    const body = {
      firstName: firstName.trim(),
      lastName: lastName.trim(),
      role,
      login: textOrNull(login),
      jobTitle: textOrNull(jobTitle),
      homeSectionId: homeSectionId || null,
      countsInRatio,
    };
    try {
      if (user) await usersApi.update(user.id, { ...body, active });
      else await usersApi.create({ ...body, password: password || null, pin: pin || null });
      await onDone();
      onClose();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{user ? `Modifier ${user.firstName} ${user.lastName}` : "Ajouter une personne"}</DialogTitle>
          <DialogDescription>Compte, rôle et place dans le taux d’encadrement.</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <FormField label="Prénom"><Input value={firstName} maxLength={80} onChange={(e) => setFirstName(e.target.value)} /></FormField>
          <FormField label="Nom"><Input value={lastName} maxLength={80} onChange={(e) => setLastName(e.target.value)} /></FormField>
          <FormField label="Rôle">
            <select
              className={selectClass}
              value={role}
              onChange={(e) => {
                const next = e.target.value as Role;
                setRole(next);
                if (!user) setCountsInRatio(next === "EDUCATRICE");
              }}
            >
              {roles.map((r) => <option key={r} value={r}>{roleLabels[r]}</option>)}
            </select>
          </FormField>
          <FormField label="Fonction (facultatif)"><Input value={jobTitle} maxLength={80} placeholder="Auxiliaire de puériculture" onChange={(e) => setJobTitle(e.target.value)} /></FormField>
          <FormField label="Section habituelle" hint="Vide : la tablette demande la section à chaque arrivée.">
            <select className={selectClass} value={homeSectionId} onChange={(e) => setHomeSectionId(e.target.value)}>
              <option value="">Toutes sections</option>
              {sections.data?.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </FormField>
          <label className="flex items-center gap-3 self-center pt-5">
            <input type="checkbox" className="size-5" checked={countsInRatio} onChange={(e) => setCountsInRatio(e.target.checked)} />
            Compte dans le taux d’encadrement
          </label>
          <FormField label="Identifiant du PC du bureau (facultatif)" className="sm:col-span-2">
            <Input value={login} maxLength={60} autoComplete="off" onChange={(e) => setLogin(e.target.value)} />
          </FormField>
          {!user && (
            <>
              <FormField label="Mot de passe (PC du bureau)" hint="12 caractères au moins.">
                <Input type="password" value={password} autoComplete="new-password" onChange={(e) => setPassword(e.target.value)} />
              </FormField>
              <FormField label="Code PIN (tablettes)" hint="4 chiffres, propre à la personne.">
                <Input inputMode="numeric" maxLength={4} value={pin} autoComplete="off" onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))} />
              </FormField>
            </>
          )}
          {user && (
            <label className="flex items-center gap-3">
              <input type="checkbox" className="size-5" checked={active} onChange={(e) => setActive(e.target.checked)} />
              Compte actif
            </label>
          )}
        </div>
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button onClick={() => void submit()}>Enregistrer</Button>
          <Button variant="ghost" onClick={onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function PinDialog({ user, onClose, onDone }: { user: UserRow; onClose: () => void; onDone: () => Promise<unknown> }) {
  const [pin, setPin] = useState("");
  const [error, setError] = useState<string | null>(null);
  const submit = async () => {
    if (!/^\d{4}$/.test(pin)) return setError("Le code PIN comporte 4 chiffres.");
    try {
      await usersApi.resetPin(user.id, pin);
      await onDone();
      onClose();
    } catch (e) {
      setError(errorMessage(e));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Code PIN de {user.firstName} {user.lastName}</DialogTitle>
          <DialogDescription>
            Le code sert à se connecter sur les tablettes et à pointer son arrivée. Il doit être différent de celui des autres
            personnes. Un nouveau code lève un blocage après des essais incorrects.
          </DialogDescription>
        </DialogHeader>
        <FormField label="Nouveau code PIN">
          <Input inputMode="numeric" maxLength={4} value={pin} autoComplete="off" onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))} />
        </FormField>
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button onClick={() => void submit()}>Enregistrer</Button>
          <Button variant="ghost" onClick={onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
