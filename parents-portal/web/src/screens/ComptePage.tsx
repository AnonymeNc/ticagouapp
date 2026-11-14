/**
 * Compte du parent (maquettes Pa7 et Pa8) : verrouillage de l’application (proposé, désactivé par défaut, ADR 0024),
 * notifications, et suppression du compte (exigence d’Apple) confirmée par un code reçu par e-mail. Supprimer efface
 * l’accès au portail et prévient la crèche ; le dossier de la famille reste à la crèche, qui le purge selon ses
 * obligations.
 */
import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router";
import { Bell, ChevronLeft, ChevronRight, Lock, Trash2 } from "lucide-react";
import { errorMessage, portal } from "../api";
import { Banner, Page, TextField, TouchButton } from "../components";
import { IS_APP, preferences } from "../native";
import { disableDevicePush } from "../push";
import { useMe } from "../session";

export const LOCK_KEY = "ticagou.parents.verrou";

export function ComptePage() {
  const me = useMe();
  const [locked, setLocked] = useState(false);
  useEffect(() => {
    void preferences.get(LOCK_KEY).then((v) => setLocked(v === "1"));
  }, []);

  return (
    <Page title="Mon compte" subtitle={me.data?.email}>
      <Link to="/" className="inline-flex min-h-11 items-center gap-1 self-start text-[16px] font-semibold text-lagon-700 no-underline">
        <ChevronLeft aria-hidden className="size-5" />La journée
      </Link>
      <ul className="m-0 flex list-none flex-col divide-y divide-border-subtle overflow-hidden rounded-lg border border-border-subtle bg-surface p-0">
        {IS_APP && (
          <li className="flex min-h-16 items-center gap-3 px-4">
            <Lock aria-hidden className="size-5 shrink-0" />
            <label htmlFor="verrou" className="flex grow flex-col">
              <span className="text-[16px] font-semibold">Verrouiller l’application</span>
              <span className="text-[14px] text-ink-muted">Face ID, empreinte ou code du téléphone à chaque ouverture</span>
            </label>
            <input id="verrou" type="checkbox" className="size-6 accent-lagon-600" checked={locked}
              onChange={(e) => {
                setLocked(e.target.checked);
                void preferences.set(LOCK_KEY, e.target.checked ? "1" : "0");
              }} />
          </li>
        )}
        <li>
          <Link to="/notifications" className="flex min-h-14 items-center gap-3 px-4 text-ink no-underline">
            <Bell aria-hidden className="size-5" /><span className="grow text-[16px] font-semibold">Notifications</span>
            <ChevronRight aria-hidden className="size-4 text-ink-muted" />
          </Link>
        </li>
        <li>
          <Link to="/compte/supprimer" className="flex min-h-14 items-center gap-3 px-4 text-danger-700 no-underline">
            <Trash2 aria-hidden className="size-5" /><span className="grow text-[16px] font-semibold">Supprimer mon compte</span>
            <ChevronRight aria-hidden className="size-4" />
          </Link>
        </li>
      </ul>
    </Page>
  );
}

export function SupprimerComptePage() {
  const me = useMe();
  const navigate = useNavigate();
  const [sent, setSent] = useState(false);
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const email = me.data?.email ?? "";

  const sendCode = async () => {
    setError(null);
    setBusy(true);
    try {
      await portal.requestLink(email);
      setSent(true);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const confirm = async () => {
    setError(null);
    setBusy(true);
    try {
      // Les appareils d’abord : la session ne vaut plus rien après la suppression.
      await disableDevicePush();
      await portal.deleteAccount(code.replace(/\s/g, ""));
      navigate("/connexion?compte=supprime", { replace: true });
    } catch (e) {
      setError(errorMessage(e));
      setBusy(false);
    }
  };

  return (
    <Page title="Supprimer mon compte">
      <section className="flex flex-col gap-2 rounded-lg border border-border-subtle bg-surface p-4">
        <h2 className="m-0 text-[17px] font-semibold">Ce qui est effacé tout de suite</h2>
        <ul className="m-0 pl-5 text-[16px]">
          <li>votre accès au portail des crèches de vos enfants ;</li>
          <li>vos sessions, appareils et notifications ;</li>
          <li>les photos et messages gardés sur ce téléphone.</li>
        </ul>
      </section>
      <section className="flex flex-col gap-2 rounded-lg border border-border-subtle bg-surface p-4">
        <h2 className="m-0 text-[17px] font-semibold">Ce qui reste à la crèche</h2>
        <p className="m-0 text-[16px]">
          Le dossier de vos enfants, les contrats et les factures sont tenus par la crèche, qui doit en garder certains. Elle
          est prévenue de votre demande et vous recontacte pour l’export ou l’effacement de ces données.
        </p>
      </section>
      {!sent ? (
        <TouchButton variant="secondary" disabled={busy || !email} onClick={() => void sendCode()}>
          Recevoir un code de confirmation
        </TouchButton>
      ) : (
        <form className="flex flex-col gap-4" onSubmit={(e) => { e.preventDefault(); void confirm(); }}>
          <TextField label={`Code envoyé à ${email}`} name="code" inputMode="numeric" autoComplete="one-time-code" maxLength={7}
            value={code} onChange={(e) => setCode(e.target.value.replace(/[^0-9 ]/g, ""))} />
          <button type="submit" disabled={busy || code.replace(/\s/g, "").length !== 6}
            className="flex min-h-touch-lg w-full items-center justify-center gap-2 rounded-lg bg-danger-700 px-5 text-[18px] font-semibold text-white disabled:opacity-50">
            <Trash2 aria-hidden className="size-5" />Supprimer définitivement mon compte
          </button>
        </form>
      )}
      {error && <Banner tone="danger">{error}</Banner>}
    </Page>
  );
}
