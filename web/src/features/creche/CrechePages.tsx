/**
 * Application Ti’Cagou Direction : choix de la crèche (ADR 0025, maquettes A1 à A4 et C8), version minimale (ADR 0027,
 * maquette C9). Dans le navigateur, rien de tout cela : la page est servie par l’instance de sa crèche.
 */
import { useState, useSyncExternalStore, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, Navigate, useLocation, useNavigate } from "react-router";
import { Building2, Check, ChevronRight, Globe, Keyboard, Plus, QrCode, RefreshCw, Trash2 } from "lucide-react";
import { Alert, FocusLayout } from "@/components/FocusLayout";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { CrecheLookupError, checkInstance, normalizeCode, parseQr, resolveCode, type FoundCreche } from "@/lib/native/annuaire";
import { loadCurrentCreche } from "@/lib/native/bootstrap";
import { chooseCreche, currentCreche, forgetCreche, listCreches, normalizeAddress, subscribeCreches, switchCreche } from "@/lib/native/creches";
import { scanQr } from "@/lib/native/device";
import { disablePushNotifications } from "@/lib/native/push";
import { api } from "@/lib/api";
import { configureTokenStorage, currentRefreshToken, refreshSession } from "@/features/auth/tokens";
import { restartApp } from "@/lib/native/restart";
import { APP_VERSION, CIBLE, compareVersions, hasNativePlugins, isNativeApp } from "@/lib/native/platform";
import { cn } from "@/lib/utils";

/** Pages de l’application accessibles sans crèche choisie. */
const WITHOUT_CRECHE = ["/creche"];

function useCreches() {
  return useSyncExternalStore(subscribeCreches, listCreches, listCreches);
}


/**
 * Dans l’application : sans crèche choisie, on la choisit d’abord ; une application trop ancienne pour l’instance
 * demande sa mise à jour. Dans le navigateur : transparent.
 */
export function NativeAppGate({ children }: { children: ReactNode }) {
  const location = useLocation();
  const creches = useCreches();
  const native = isNativeApp();
  const creche = native ? currentCreche() : null;
  const compat = useQuery({
    queryKey: ["app-compat", creche?.adresse],
    queryFn: ({ signal }) => checkInstance(creche!.adresse, signal),
    enabled: creche !== null,
    staleTime: 60 * 60_000,
    retry: false,
  });
  if (!native) return children;
  if (!creche && !WITHOUT_CRECHE.some((p) => location.pathname.startsWith(p))) {
    return <Navigate to={creches.length > 0 ? "/creche/mes-creches" : "/creche"} replace />;
  }
  const minimum = compat.data?.minimum?.[CIBLE];
  if (creche && minimum && compareVersions(APP_VERSION, minimum) < 0 && CIBLE === "direction") {
    return <UpdateRequired current={APP_VERSION} minimum={minimum} adresse={creche.adresse} />;
  }
  return children;
}

function UpdateRequired({ current, minimum, adresse }: { current: string; minimum: string; adresse: string }) {
  return (
    <FocusLayout>
      <div className="flex flex-col items-center gap-4 text-center">
        <span className="grid size-20 place-items-center rounded-3xl bg-warning-100 text-warning-700"><RefreshCw aria-hidden className="size-10" /></span>
        <h1 className="m-0 font-display text-title-1">Mettez à jour Ti’Cagou</h1>
        <p className="m-0 text-muted-foreground">
          Votre crèche utilise une version plus récente. Cette application ({current}) n’est plus compatible : la version
          {" "}{minimum} ou plus est demandée. Ouvrez l’App Store ou Google Play pour la mettre à jour.
        </p>
        <Button asChild variant="ghost"><a href={adresse}>Continuer dans le navigateur</a></Button>
      </div>
    </FocusLayout>
  );
}

type Step = { kind: "menu" } | { kind: "code" } | { kind: "adresse" } | { kind: "confirm"; found: FoundCreche };

/** A1 à A3 : scanner le QR, taper le code, ou taper l’adresse ; puis confirmer le nom de la crèche. */
export function CrecheChoicePage() {
  const [step, setStep] = useState<Step>({ kind: "menu" });
  const [value, setValue] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const hasOthers = useCreches().length > 0;

  const lookup = async (run: () => Promise<FoundCreche>) => {
    setError(null);
    setBusy(true);
    try {
      setStep({ kind: "confirm", found: await run() });
    } catch (e) {
      setError(e instanceof CrecheLookupError ? e.message : "La crèche ne répond pas. Réessayez dans un instant.");
    } finally {
      setBusy(false);
    }
  };

  /** L’instance confirme son nom : c’est elle qui fait foi, pas l’annuaire. */
  const fromAddress = async (adresse: string, code: string | null, hint?: FoundCreche): Promise<FoundCreche> => {
    const compat = await checkInstance(adresse);
    return { nom: compat.creche ?? hint?.nom ?? adresse, commune: hint?.commune ?? null, adresse, code: code ?? compat.code };
  };

  const onScan = async () => {
    const text = await scanQr("Visez le QR affiché sur l’ordinateur de la crèche");
    if (!text) return;
    const parsed = parseQr(text);
    if (!parsed) {
      setError("Ce QR code n’est pas celui d’une crèche Ti’Cagou.");
      return;
    }
    await lookup(async () => {
      if ("adresse" in parsed) return fromAddress(parsed.adresse, null);
      const entry = await resolveCode(parsed.code);
      return fromAddress(entry.adresse, parsed.code, entry);
    });
  };

  const onCode = () => {
    const code = normalizeCode(value);
    if (!code) {
      setError("Le code a 6 caractères, lettres et chiffres (sans 0, O, 1, I ni L).");
      return;
    }
    void lookup(async () => {
      const entry = await resolveCode(code);
      return fromAddress(entry.adresse, code, entry);
    });
  };

  const onAddress = () => {
    const adresse = normalizeAddress(value);
    if (!adresse) {
      setError("Adresse invalide : elle commence par https:// (par exemple https://ma-creche.ticagou.nc).");
      return;
    }
    void lookup(() => fromAddress(adresse, null));
  };

  const onConfirm = async (found: FoundCreche) => {
    await chooseCreche(found);
    await loadCurrentCreche();
    restartApp("/login");
  };

  if (step.kind === "confirm") {
    const { found } = step;
    return (
      <FocusLayout>
        <h1 className="m-0 font-display text-title-1">Votre crèche</h1>
        <div className="mt-4 flex items-center gap-3 rounded-lg border-2 border-success-600 p-4">
          <span className="grid size-10 shrink-0 place-items-center rounded-md bg-success-100 text-success-700"><Building2 aria-hidden className="size-5" /></span>
          <div className="min-w-0 flex-1">
            <p className="m-0 font-semibold">{found.nom}</p>
            <p className="m-0 truncate text-small text-muted-foreground">{[found.commune, found.adresse.replace(/^https?:\/\//, "")].filter(Boolean).join(" · ")}</p>
          </div>
          <Check aria-label="Trouvée" className="size-5 text-success-700" />
        </div>
        <p className="mt-3 mb-0 text-small text-muted-foreground">Vérifiez qu’il s’agit bien de votre crèche avant de continuer.</p>
        <div className="mt-6 flex flex-col gap-2">
          <Button className="h-12" onClick={() => void onConfirm(found)}>Continuer</Button>
          <Button variant="ghost" onClick={() => setStep({ kind: "menu" })}>Ce n’est pas ma crèche</Button>
        </div>
      </FocusLayout>
    );
  }

  if (step.kind === "code" || step.kind === "adresse") {
    const isCode = step.kind === "code";
    return (
      <FocusLayout>
        <h1 className="m-0 font-display text-title-1">{isCode ? "Code de la crèche" : "Adresse de la crèche"}</h1>
        <form
          className="mt-4 flex flex-col gap-4"
          onSubmit={(e) => {
            e.preventDefault();
            if (isCode) onCode();
            else onAddress();
          }}
        >
          <label className="flex flex-col gap-1.5">
            <span className="text-label">{isCode ? "6 caractères, sur la fiche de mise en service" : "Adresse donnée par Ti’Cagou"}</span>
            <Input
              autoFocus
              value={value}
              onChange={(e) => setValue(e.target.value)}
              autoCapitalize={isCode ? "characters" : "none"}
              autoCorrect="off"
              spellCheck={false}
              inputMode={isCode ? "text" : "url"}
              placeholder={isCode ? "NK7Q2M" : "https://ma-creche.ticagou.nc"}
              className={cn("h-12", isCode && "text-center font-display text-title-2 tracking-[0.3em] uppercase")}
            />
          </label>
          {error && <Alert>{error}</Alert>}
          <Button type="submit" className="h-12" disabled={busy}>{busy ? "Recherche…" : "Rechercher"}</Button>
          <Button type="button" variant="ghost" onClick={() => { setError(null); setValue(""); setStep({ kind: "menu" }); }}>Retour</Button>
        </form>
      </FocusLayout>
    );
  }

  const choice = (icon: ReactNode, title: string, hint: string, onClick: () => void, primary = false) => (
    <button
      type="button"
      onClick={onClick}
      disabled={busy}
      className={cn(
        "flex w-full items-center gap-3 rounded-lg border bg-card p-4 text-left hover:bg-lagon-50",
        primary ? "border-2 border-lagon-600" : "border-border-subtle",
      )}
    >
      <span className={cn("grid size-10 shrink-0 place-items-center rounded-md", primary ? "bg-lagon-100 text-lagon-700" : "bg-muted")}>{icon}</span>
      <span className="min-w-0 flex-1">
        <span className="block font-semibold">{title}</span>
        <span className="block text-small text-muted-foreground">{hint}</span>
      </span>
      <ChevronRight aria-hidden className="size-4 text-muted-foreground" />
    </button>
  );

  return (
    <FocusLayout footer={hasOthers ? <Link to="/creche/mes-creches" className="font-semibold text-lagon-700">Mes crèches</Link> : undefined}>
      <h1 className="m-0 font-display text-title-1">Bienvenue</h1>
      <p className="mt-2 mb-5 text-muted-foreground">Pour commencer, indiquez la crèche dans laquelle vous travaillez.</p>
      <div className="flex flex-col gap-3">
        {hasNativePlugins() &&
          choice(<QrCode aria-hidden className="size-5" />, "Scanner le QR de la crèche", "Sur l’ordinateur : Paramètres › Postes › Ouvrir sur mon téléphone", () => void onScan(), true)}
        {choice(<Keyboard aria-hidden className="size-5" />, "Taper le code de la crèche", "6 caractères, sur la fiche de mise en service", () => { setError(null); setValue(""); setStep({ kind: "code" }); }, !hasNativePlugins())}
        {choice(<Globe aria-hidden className="size-5" />, "Autre adresse", "https://… donnée par Ti’Cagou", () => { setError(null); setValue(""); setStep({ kind: "adresse" }); })}
      </div>
      {error && <div className="mt-4"><Alert>{error}</Alert></div>}
      <p className="mt-6 mb-0 text-center text-small text-muted-foreground">
        Vous êtes parent ? Téléchargez plutôt l’application <strong>Ti’Cagou Parents</strong>.
      </p>
    </FocusLayout>
  );
}

/** C8 : crèches connues du téléphone ; changer, ajouter, oublier. */
export function MesCrechesPage() {
  const navigate = useNavigate();
  const creches = useCreches();
  const current = currentCreche();
  const [forgetting, setForgetting] = useState<string | null>(null);

  return (
    <FocusLayout>
      <h1 className="m-0 font-display text-title-1">Mes crèches</h1>
      <ul className="mt-4 mb-0 flex list-none flex-col divide-y divide-border-subtle overflow-hidden rounded-lg border border-border-subtle p-0">
        {creches.map((c) => (
          <li key={c.id} className="flex items-center gap-3 p-3">
            <Building2 aria-hidden className={cn("size-5 shrink-0", c.id === current?.id ? "text-lagon-600" : "text-muted-foreground")} />
            <button
              type="button"
              className="min-w-0 flex-1 text-left"
              onClick={async () => {
                if (c.id === current?.id) return navigate("/");
                await switchCreche(c.id);
                await loadCurrentCreche();
                restartApp("/");
              }}
            >
              <span className="block font-semibold">{c.nom}</span>
              <span className="block truncate text-small text-muted-foreground">{[c.commune, c.adresse.replace(/^https?:\/\//, "")].filter(Boolean).join(" · ")}</span>
            </button>
            {c.id === current?.id && <span className="rounded-full bg-lagon-100 px-2 py-0.5 text-caption text-lagon-700">Actuelle</span>}
            <Button variant="ghost" size="icon-sm" aria-label={`Oublier ${c.nom}`} onClick={() => setForgetting(c.id)}>
              <Trash2 aria-hidden />
            </Button>
          </li>
        ))}
        {creches.length === 0 && <li className="p-3 text-muted-foreground">Aucune crèche sur ce téléphone.</li>}
      </ul>
      <Button asChild variant="outline" className="mt-4 h-12 w-full"><Link to="/creche"><Plus aria-hidden />Ajouter une crèche</Link></Button>
      {forgetting && (
        <div className="mt-4 rounded-lg border border-danger-100 bg-card p-4">
          <p className="m-0 font-semibold">Oublier « {creches.find((c) => c.id === forgetting)?.nom} » ?</p>
          <p className="mt-1 mb-3 text-small text-muted-foreground">
            Ferme la session, coupe les notifications et efface de ce téléphone tout ce qui concerne cette crèche.
          </p>
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => setForgetting(null)}>Annuler</Button>
            <Button
              variant="destructive"
              onClick={async () => {
                const wasCurrent = forgetting === current?.id;
                await forgetWithCleanup(forgetting, current?.id ?? null);
                setForgetting(null);
                restartApp(wasCurrent ? "/creche" : "/creche/mes-creches");
              }}
            >
              <Trash2 aria-hidden />Oublier cette crèche
            </Button>
          </div>
        </div>
      )}
    </FocusLayout>
  );
}

/**
 * Oublier une crèche (ADR 0025) : son serveur oublie d’abord ce téléphone (notifications, session), puis tout ce qui la
 * concerne est effacé du téléphone. Pour une autre crèche que la courante, sa session est rouverte le temps du ménage.
 */
async function forgetWithCleanup(id: string, currentId: string | null) {
  try {
    if (id !== currentId) {
      await switchCreche(id);
      await loadCurrentCreche();
      configureTokenStorage({ persist: true });
      await refreshSession();
    }
    await disablePushNotifications();
    const token = currentRefreshToken();
    if (token) await api("/auth/logout", { method: "POST", body: { refreshToken: token }, auth: false }).catch(() => {});
  } catch {
    // Crèche injoignable : on efface quand même ce téléphone.
  }
  await forgetCreche(id);
  if (currentId && currentId !== id) {
    await switchCreche(currentId);
    await loadCurrentCreche();
  }
}

/** A4 : rappel de la crèche au-dessus de la connexion, avec « Changer ». */
export function CurrentCrecheCard() {
  const creche = isNativeApp() ? currentCreche() : null;
  if (!creche) return null;
  return (
    <div className="mb-4 flex items-center gap-3 rounded-md border border-border-subtle p-3">
      <Building2 aria-hidden className="size-5 text-muted-foreground" />
      <div className="min-w-0 flex-1">
        <p className="m-0 text-label">{creche.nom}</p>
        {creche.commune && <p className="m-0 text-small text-muted-foreground">{creche.commune}</p>}
      </div>
      <Link to="/creche/mes-creches" className="text-label text-lagon-700">Changer</Link>
    </div>
  );
}
