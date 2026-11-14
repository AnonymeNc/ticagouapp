import { useState } from "react";
import { Navigate, useNavigate, useSearchParams } from "react-router";
import { useQueryClient } from "@tanstack/react-query";
import { CircleCheck, Receipt, ShieldCheck, Tablet } from "lucide-react";
import { Alert, FocusLayout } from "@/components/FocusLayout";
import { Button } from "@/components/ui/button";
import { api, ApiError, errorMessage } from "@/lib/api";
import { useAuth } from "@/features/auth/AuthProvider";
import { sessionHeaders } from "@/features/auth/tokens";
import { Splash } from "@/features/auth/DesktopGate";
import { setupStatusKey, useSetupStatus } from "@/features/auth/useSetupStatus";
import type { SessionResponse } from "@/features/auth/types";
import { defaultSections, describeMonths, toSetupRequest, type WizardData } from "./setupSchemas";
import {
  AdminStep,
  AgrementStep,
  IdentityStep,
  SectionsStep,
  StepActions,
  StepTitle,
  WelcomeStep,
} from "./SetupSteps";

/**
 * Assistant de premier lancement (prompt 10 de prompts/claude-design.md, pas de maquette exportée) :
 * bienvenue → structure → agrément → sections → compte de direction → récapitulatif. L'adresse vient du lien de démarrage
 * remis par Ti'Cagou, qui porte aussi le jeton à usage unique (ADR 0013). Les tablettes s'appairent ensuite dans Paramètres.
 */
type Step = "welcome" | "identity" | "agrement" | "sections" | "admin" | "recap" | "done";

const progressSteps: Step[] = ["identity", "agrement", "sections", "admin", "recap"];

const emptyData: WizardData = {
  identity: { name: "", city: "" },
  agrement: { ridet: "", agrementNumber: "", agrementDate: "", capacity: "" },
  sections: defaultSections,
  admin: { firstName: "", lastName: "", login: "", password: "", confirmation: "" },
};

export function SetupWizard() {
  const status = useSetupStatus();
  const { applySession } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [step, setStep] = useState<Step>("welcome");
  const [data, setData] = useState<WizardData>(emptyData);
  const [submitError, setSubmitError] = useState<ApiError | string | null>(null);
  const [pending, setPending] = useState(false);
  const [params] = useSearchParams();
  const token = params.get("jeton");

  if (status.isPending) return <Splash />;
  // Déjà installé (hors écran final) : rien à faire ici.
  if (status.data?.initialized && step !== "done") return <Navigate to="/login" replace />;

  const progress = progressSteps.indexOf(step);

  async function submit() {
    setPending(true);
    setSubmitError(null);
    try {
      const session = await api<SessionResponse>("/setup", {
        method: "POST",
        body: toSetupRequest(data),
        auth: false,
        headers: { ...sessionHeaders(), ...(token ? { "X-Ticagou-Setup": token } : {}) },
      });
      applySession(session);
      setStep("done");
      await queryClient.invalidateQueries({ queryKey: setupStatusKey });
    } catch (e) {
      setSubmitError(e instanceof ApiError ? e : errorMessage(e));
    } finally {
      setPending(false);
    }
  }

  let content;
  switch (step) {
    case "welcome":
      content = <WelcomeStep onNext={() => setStep("identity")} />;
      break;
    case "identity":
      content = (
        <IdentityStep
          defaultValues={data.identity}
          onBack={() => setStep("welcome")}
          onNext={(identity) => {
            setData({ ...data, identity });
            setStep("agrement");
          }}
        />
      );
      break;
    case "agrement":
      content = (
        <AgrementStep
          defaultValues={data.agrement}
          onBack={() => setStep("identity")}
          onNext={(agrement) => {
            setData({ ...data, agrement });
            setStep("sections");
          }}
        />
      );
      break;
    case "sections":
      content = (
        <SectionsStep
          defaultValues={data.sections}
          capacity={Number(data.agrement.capacity)}
          onBack={() => setStep("agrement")}
          onNext={(sections) => {
            setData({ ...data, sections });
            setStep("admin");
          }}
        />
      );
      break;
    case "admin":
      content = (
        <AdminStep
          defaultValues={data.admin}
          onBack={() => setStep("sections")}
          onNext={(admin) => {
            setData({ ...data, admin });
            setStep("recap");
          }}
        />
      );
      break;
    case "recap":
      content = (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void submit();
          }}
        >
          <StepTitle title="Tout est prêt ?">Vérifiez ces informations avant de créer votre structure.</StepTitle>
          <Recap data={data} onEdit={setStep} />
          {submitError && (
            <div className="mt-4">
              <Alert>
                {typeof submitError === "string" ? submitError : submitError.message}
                {submitError instanceof ApiError && Object.keys(submitError.errors).length > 0 && (
                  <ul className="mt-2 mb-0">
                    {Object.entries(submitError.errors).map(([field, message]) => (
                      <li key={field}>{message}</li>
                    ))}
                  </ul>
                )}
              </Alert>
            </div>
          )}
          <StepActions onBack={() => setStep("admin")} nextLabel={pending ? "Création…" : "Créer ma structure"} pending={pending} />
        </form>
      );
      break;
    case "done":
      content = (
        <>
          <CircleCheck aria-hidden className="mb-2 size-10 text-success-600" />
          <StepTitle title="Votre structure est prête">
            {data.identity.name} est en ligne. Trois choses à savoir pour la suite :
          </StepTitle>
          <ul className="m-0 flex list-none flex-col gap-3 p-0">
            <li className="flex gap-3">
              <Receipt aria-hidden className="size-5 shrink-0 text-lagon-600" />
              <span>
                <strong className="font-semibold">Barème des tarifs</strong> — Paramètres › Barème et facturation
                <span className="block text-small text-muted-foreground">À saisir en premier : sans barème, aucun contrat d’inscription ne peut être établi.</span>
              </span>
            </li>
            <li className="flex gap-3">
              <ShieldCheck aria-hidden className="size-5 shrink-0 text-lagon-600" />
              <span>
                <strong className="font-semibold">Sauvegardes</strong> — rien à faire
                <span className="block text-small text-muted-foreground">Elles sont faites chaque nuit, chiffrées et copiées hors site par Ti’Cagou.</span>
              </span>
            </li>
            <li className="flex gap-3">
              <Tablet aria-hidden className="size-5 shrink-0 text-lagon-600" />
              <span>
                <strong className="font-semibold">Tablette d’accueil</strong> — Paramètres › Tablettes
                <span className="block text-small text-muted-foreground">Appairez-la en scannant le QR code affiché.</span>
              </span>
            </li>
          </ul>
          <Button className="mt-8 h-10 px-6" onClick={() => navigate("/", { replace: true })} autoFocus>
            Ouvrir le tableau de bord
          </Button>
        </>
      );
      break;
  }

  return (
    <FocusLayout>
      {progress >= 0 && (
        <div className="mb-6">
          <p className="m-0 mb-2 text-caption text-muted-foreground uppercase">
            Étape {progress + 1} sur {progressSteps.length}
          </p>
          <div
            className="h-1.5 overflow-hidden rounded-full bg-muted"
            role="progressbar"
            aria-valuemin={1}
            aria-valuemax={progressSteps.length}
            aria-valuenow={progress + 1}
            aria-label="Progression"
          >
            <div className="h-full bg-lagon-600 transition-all" style={{ width: `${((progress + 1) / progressSteps.length) * 100}%` }} />
          </div>
        </div>
      )}
      {content}
    </FocusLayout>
  );
}

function Recap({ data, onEdit }: { data: WizardData; onEdit: (step: Step) => void }) {
  const row = (label: string, value: string) => (
    <div className="flex justify-between gap-4">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="m-0 text-right">{value || "—"}</dd>
    </div>
  );
  const block = (title: string, target: Step, children: React.ReactNode) => (
    <section className="rounded-md border border-border-subtle p-4">
      <div className="mb-2 flex items-center justify-between">
        <h2 className="m-0 text-title-3">{title}</h2>
        <Button type="button" variant="link" size="sm" onClick={() => onEdit(target)}>
          Modifier
        </Button>
      </div>
      <dl className="m-0 flex flex-col gap-1">{children}</dl>
    </section>
  );
  return (
    <div className="flex flex-col gap-3">
      {block("Structure", "identity", <>
        {row("Nom", data.identity.name)}
        {row("Commune", data.identity.city)}
      </>)}
      {block("Agrément", "agrement", <>
        {row("Capacité", `${data.agrement.capacity} places`)}
        {row("Numéro", data.agrement.agrementNumber)}
        {row("Date", data.agrement.agrementDate)}
        {row("RIDET", data.agrement.ridet)}
      </>)}
      {block("Sections", "sections", data.sections.map((s) => (
        <div key={s.name} className="flex justify-between gap-4">
          <dt className="flex items-center gap-2">
            <span aria-hidden className="size-3 rounded-full" style={{ backgroundColor: s.color }} />
            {s.name}
          </dt>
          <dd className="m-0 text-right text-muted-foreground">
            {describeMonths(s.minAgeMonths)} → {s.maxAgeMonths ? describeMonths(s.maxAgeMonths) : "école"} · {s.capacity} places
          </dd>
        </div>
      )))}
      {block("Compte de direction", "admin", <>
        {row("Nom", `${data.admin.firstName} ${data.admin.lastName}`)}
        {row("Identifiant", data.admin.login)}
      </>)}
    </div>
  );
}
