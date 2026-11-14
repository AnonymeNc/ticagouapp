import { useEffect, useState } from "react";
import { Link } from "react-router";
import { Bell, BellOff, ChevronLeft, Info } from "lucide-react";
import { errorMessage } from "../api";
import { Banner, Page, TouchButton } from "../components";
import { IS_APP } from "../native";
import {
  currentPush, defaultCategories, disableDevicePush, enableDevicePush, pushCategories, pushSupport, saveDeviceCategories,
  type CurrentPush, type PushCategory,
} from "../push";

/**
 * Notifications sur ce téléphone (ADR 0018, maquette « Parents — Activer les notifications » du lot 1) : activation,
 * catégories choisies pour cet appareil, aide pour l'iPhone.
 */
export function NotificationsPage() {
  const [support] = useState(pushSupport);
  const [subscription, setSubscription] = useState<CurrentPush | null>(null);
  const [categories, setCategories] = useState<PushCategory[]>(defaultCategories);
  const [loading, setLoading] = useState(support === "ok");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    if (support !== "ok") return;
    let active = true;
    (async () => {
      try {
        const current = await currentPush();
        if (!active) return;
        setSubscription(current);
        if (current) setCategories(current.categories);
      } catch (e) {
        if (active) setError(errorMessage(e));
      } finally {
        if (active) setLoading(false);
      }
    })();
    return () => {
      active = false;
    };
  }, [support]);

  const activate = async () => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      if (!(await enableDevicePush(categories))) {
        setError(IS_APP
          ? "Les notifications ne sont pas autorisées : autorisez-les pour Ti’Cagou dans les réglages du téléphone."
          : "Les notifications ne sont pas autorisées : autorisez-les dans les réglages du navigateur pour ce site.");
        return;
      }
      setSubscription(await currentPush());
      setNotice("Notifications activées sur ce téléphone.");
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const toggle = async (id: PushCategory, on: boolean) => {
    const next = on ? [...categories, id] : categories.filter((c) => c !== id);
    setCategories(next);
    if (!subscription) return;
    try {
      await saveDeviceCategories(subscription, next);
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const deactivate = async () => {
    setBusy(true);
    await disableDevicePush();
    setSubscription(null);
    setBusy(false);
    setNotice("Ce téléphone ne reçoit plus de notifications.");
  };

  return (
    <Page title="Notifications" subtitle="Soyez prévenu sur ce téléphone quand la crèche partage quelque chose.">
      <Link to="/" className="inline-flex min-h-11 items-center gap-1 self-start text-[16px] font-semibold text-lagon-700 no-underline">
        <ChevronLeft aria-hidden className="size-5" />La journée
      </Link>

      {support === "ios-install" && (
        <Banner>
          Sur iPhone, ajoutez d’abord l’application à l’écran d’accueil : bouton Partager, puis « Sur l’écran d’accueil ».
          Ouvrez-la depuis cette icône pour activer les notifications.
        </Banner>
      )}
      {support === "unsupported" && <Banner>Ce navigateur ne permet pas les notifications. Essayez avec Chrome, Safari ou Firefox à jour.</Banner>}
      {support === "denied" && (
        <Banner tone="warning">Les notifications sont bloquées pour ce site : autorisez-les dans les réglages du navigateur, puis revenez ici.</Banner>
      )}

      {support === "ok" && !loading && (
        <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-surface p-4 shadow-[var(--shadow-1)]">
          <div className="flex items-start gap-3">
            <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-lagon-50 text-lagon-700">
              <Info aria-hidden className="size-5" />
            </span>
            <p className="m-0 text-[16px]">
              Les notifications ne contiennent jamais d’information de santé ni le texte des messages : seulement « Nouvelle
              photo de … » ou « Nouveau message de la crèche ».
            </p>
          </div>
          {subscription ? (
            <TouchButton variant="secondary" disabled={busy} onClick={() => void deactivate()}>
              <BellOff aria-hidden className="size-5" />Désactiver sur ce téléphone
            </TouchButton>
          ) : (
            <TouchButton disabled={busy || categories.length === 0} onClick={() => void activate()}>
              <Bell aria-hidden className="size-5" />Activer les notifications
            </TouchButton>
          )}
        </section>
      )}

      {notice && <Banner tone="success">{notice}</Banner>}
      {error && <Banner tone="danger">{error}</Banner>}

      {support === "ok" && !loading && (
        <fieldset className="m-0 flex flex-col gap-1 border-0 p-0">
          <legend className="mb-2 text-[17px] font-semibold">Me prévenir pour</legend>
          {pushCategories.map((c) => (
            <label key={c.id} className="flex min-h-14 items-center gap-3 rounded-md border border-border-subtle bg-surface px-4">
              <span className="flex grow flex-col">
                <span className="text-[16px] font-semibold">{c.label}</span>
                <span className="text-[14px] text-ink-muted">{c.hint}</span>
              </span>
              <input type="checkbox" className="size-6 accent-lagon-600" checked={categories.includes(c.id)}
                onChange={(e) => void toggle(c.id, e.target.checked)} />
            </label>
          ))}
        </fieldset>
      )}
    </Page>
  );
}
