import { useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { CircleCheck } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/api";
import { parseDateFr } from "@/lib/datetime";
import { selectClass } from "@/lib/formFields";
import { cn } from "@/lib/utils";
import { onlineApi, onlineKeys } from "./online";

const DAYS = [
  [1, "Lundi"], [2, "Mardi"], [3, "Mercredi"], [4, "Jeudi"], [5, "Vendredi"],
] as const;

/**
 * Pré-inscription en ligne (lot 3, ADR 0020) : page publique de la crèche, sans compte, intégrable au site de la
 * crèche (lien ou cadre). La demande arrive dans le tableau des inscriptions, marquée « En ligne ». Écart : pas de
 * maquette dédiée ; reprend les champs de « Nouvelle demande » sans la section ni la famille, que fixe la direction.
 */
export function PreinscriptionPage() {
  const form = useQuery({ queryKey: onlineKeys.publicForm, queryFn: ({ signal }) => onlineApi.publicForm(signal) });
  const startedAt = useRef(Date.now());
  const [child, setChild] = useState({ first: "", last: "", birth: "", unborn: false, start: "" });
  const [guardian, setGuardian] = useState({ first: "", last: "", relationship: "Mère", phone: "", email: "" });
  const [days, setDays] = useState<number[]>([]);
  const [hours, setHours] = useState({ arrives: "07:30", leaves: "16:30" });
  const [criteria, setCriteria] = useState<string[]>([]);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [consent, setConsent] = useState(false);
  const [website, setWebsite] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const page = (children: React.ReactNode) => (
    <main className="min-h-dvh bg-background px-4 py-8 text-foreground">
      <div className="mx-auto flex max-w-2xl flex-col gap-5">{children}</div>
    </main>
  );

  if (form.isPending) return page(<p className="m-0 text-muted-foreground">Chargement…</p>);
  if (form.isError) return page(<Alert>{errorMessage(form.error)}</Alert>);
  const data = form.data!;
  if (!data.enabled) {
    return page(<>
      <h1 className="m-0 font-display text-display">{data.structureName}</h1>
      <p className="m-0">La crèche ne reçoit pas de pré-inscription en ligne pour le moment. Contactez-la directement.</p>
    </>);
  }
  if (done) {
    return page(<div role="status" className="flex flex-col items-center gap-3 rounded-lg bg-success-100 p-8 text-center text-success-700">
      <CircleCheck aria-hidden className="size-10" />
      <p className="m-0 text-title-2">{done}</p>
    </div>);
  }

  const submit = async () => {
    setError(null);
    const birth = child.birth.trim() ? parseDateFr(child.birth) : null;
    const start = parseDateFr(child.start);
    if (!child.first.trim() || !child.last.trim()) return setError("Indiquez le prénom et le nom de l’enfant.");
    if (!birth) return setError(child.unborn ? "Indiquez la date de naissance prévue (JJ/MM/AAAA)." : "Indiquez la date de naissance (JJ/MM/AAAA).");
    if (!start) return setError("Indiquez la date d’entrée souhaitée (JJ/MM/AAAA).");
    if (days.length === 0) return setError("Choisissez au moins un jour d’accueil.");
    if (!guardian.first.trim() || !guardian.last.trim()) return setError("Indiquez votre prénom et votre nom.");
    if (!guardian.phone.trim() && !guardian.email.trim()) return setError("Indiquez un téléphone ou une adresse e-mail.");
    const missing = data.questions.find((q) => q.required && !answers[q.id]?.trim());
    if (missing) return setError(`Répondez à la question : ${missing.label}`);
    if (!consent) return setError("Cochez la case de consentement pour envoyer votre demande.");
    setBusy(true);
    try {
      const result = await onlineApi.submit({
        request: {
          childFirstName: child.first.trim(), childLastName: child.last.trim(),
          birthDate: child.unborn ? null : birth, expectedBirthDate: child.unborn ? birth : null,
          desiredStart: start, careType: "REGULIER", arrivesAt: hours.arrives || null, leavesAt: hours.leaves || null, days,
          guardianFirstName: guardian.first.trim(), guardianLastName: guardian.last.trim(), relationship: guardian.relationship.trim(),
          phone: guardian.phone.trim() || null, email: guardian.email.trim() || null, criteria,
        },
        answers, consent, website, startedAt: startedAt.current,
      });
      setDone(result.message);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  return page(<>
    <header className="flex flex-col gap-2">
      <h1 className="m-0 font-display text-display">Pré-inscription — {data.structureName}</h1>
      {data.intro && <p className="m-0 whitespace-pre-wrap">{data.intro}</p>}
      <p className="m-0 text-muted-foreground">La crèche étudie chaque demande et vous recontacte. Rien n’est encore réservé.</p>
    </header>

    <form className="flex flex-col gap-5" onSubmit={(e) => { e.preventDefault(); void submit(); }} noValidate>
      <fieldset className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <legend className="px-1 text-title-3">L’enfant</legend>
        <div className="grid gap-4 sm:grid-cols-2">
          <FormField label="Prénom"><Input value={child.first} maxLength={80} onChange={(e) => setChild({ ...child, first: e.target.value })} /></FormField>
          <FormField label="Nom"><Input value={child.last} maxLength={80} onChange={(e) => setChild({ ...child, last: e.target.value })} /></FormField>
        </div>
        <label className="flex items-center gap-2">
          <input type="checkbox" checked={child.unborn} onChange={(e) => setChild({ ...child, unborn: e.target.checked })} className="size-5" />
          L’enfant n’est pas encore né
        </label>
        <div className="grid gap-4 sm:grid-cols-2">
          <FormField label={child.unborn ? "Naissance prévue" : "Date de naissance"} hint="JJ/MM/AAAA">
            <Input value={child.birth} inputMode="numeric" onChange={(e) => setChild({ ...child, birth: e.target.value })} />
          </FormField>
          <FormField label="Entrée souhaitée" hint="JJ/MM/AAAA">
            <Input value={child.start} inputMode="numeric" onChange={(e) => setChild({ ...child, start: e.target.value })} />
          </FormField>
        </div>
        <fieldset className="m-0 flex flex-col gap-2 border-0 p-0">
          <legend className="mb-1 text-label">Jours d’accueil souhaités</legend>
          <div className="flex flex-wrap gap-2">
            {DAYS.map(([d, label]) => (
              <button key={d} type="button" aria-pressed={days.includes(d)}
                onClick={() => setDays(days.includes(d) ? days.filter((x) => x !== d) : [...days, d].sort())}
                className={cn("h-control rounded-pill border px-4", days.includes(d) ? "border-lagon-600 bg-lagon-100 text-lagon-700" : "border-border bg-card")}>
                {label}
              </button>
            ))}
          </div>
        </fieldset>
        <div className="grid gap-4 sm:grid-cols-2">
          <FormField label="Arrivée vers"><Input type="time" value={hours.arrives} onChange={(e) => setHours({ ...hours, arrives: e.target.value })} /></FormField>
          <FormField label="Départ vers"><Input type="time" value={hours.leaves} onChange={(e) => setHours({ ...hours, leaves: e.target.value })} /></FormField>
        </div>
      </fieldset>

      <fieldset className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <legend className="px-1 text-title-3">Vous</legend>
        <div className="grid gap-4 sm:grid-cols-2">
          <FormField label="Votre prénom"><Input value={guardian.first} maxLength={80} onChange={(e) => setGuardian({ ...guardian, first: e.target.value })} /></FormField>
          <FormField label="Votre nom"><Input value={guardian.last} maxLength={80} onChange={(e) => setGuardian({ ...guardian, last: e.target.value })} /></FormField>
          <FormField label="Lien avec l’enfant"><Input value={guardian.relationship} maxLength={60} onChange={(e) => setGuardian({ ...guardian, relationship: e.target.value })} /></FormField>
          <FormField label="Téléphone"><Input value={guardian.phone} inputMode="tel" maxLength={30} onChange={(e) => setGuardian({ ...guardian, phone: e.target.value })} /></FormField>
          <FormField label="E-mail" className="sm:col-span-2"><Input type="email" value={guardian.email} maxLength={150} onChange={(e) => setGuardian({ ...guardian, email: e.target.value })} /></FormField>
        </div>
      </fieldset>

      {(data.criteria.length > 0 || data.questions.length > 0) && (
        <fieldset className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
          <legend className="px-1 text-title-3">Votre situation</legend>
          {data.criteria.map((c) => (
            <label key={c.id} className="flex items-center gap-2">
              <input type="checkbox" className="size-5" checked={criteria.includes(c.id)}
                onChange={(e) => setCriteria(e.target.checked ? [...criteria, c.id] : criteria.filter((x) => x !== c.id))} />
              {c.label}
            </label>
          ))}
          {data.questions.map((q) => (
            <FormField key={q.id} label={q.required ? q.label : `${q.label} (facultatif)`}>
              {q.kind === "TEXTE" ? (
                <Textarea rows={2} maxLength={1000} value={answers[q.id] ?? ""} onChange={(e) => setAnswers({ ...answers, [q.id]: e.target.value })} />
              ) : (
                <select className={selectClass} value={answers[q.id] ?? ""} onChange={(e) => setAnswers({ ...answers, [q.id]: e.target.value })}>
                  <option value="">Choisissez…</option>
                  {(q.kind === "OUI_NON" ? ["Oui", "Non"] : q.options).map((o) => <option key={o} value={o}>{o}</option>)}
                </select>
              )}
            </FormField>
          ))}
        </fieldset>
      )}

      {/* Piège à robots : invisible et hors de la tabulation, un humain le laisse vide. */}
      <div aria-hidden="true" style={{ position: "absolute", left: "-10000px", width: 1, height: 1, overflow: "hidden" }}>
        <label>Site web<input tabIndex={-1} autoComplete="off" value={website} onChange={(e) => setWebsite(e.target.value)} /></label>
      </div>

      <label className="flex items-start gap-3 rounded-lg bg-muted p-4">
        <input type="checkbox" className="mt-1 size-5" checked={consent} onChange={(e) => setConsent(e.target.checked)} />
        <span>
          J’accepte que la crèche enregistre ces informations pour étudier ma demande. Sans suite, elles sont effacées
          12 mois après le dernier échange.
        </span>
      </label>
      {error && <Alert>{error}</Alert>}
      <Button type="submit" size="lg" disabled={busy} className="self-start">Envoyer ma demande</Button>
    </form>
  </>);
}
