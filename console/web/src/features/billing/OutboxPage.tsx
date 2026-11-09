import { useEffect, useState } from "react";
import { Link } from "react-router";
import { FileText, Send } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Pill } from "@/components/ui/pill";
import { shortStamp } from "@/features/parc/labels";
import { errorMessage } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { cn } from "@/lib/utils";
import { billingApi, useBillingWrite, useOutbox, type OutboxMessage } from "./api";
import { xpf } from "./labels";

/** Pastille d'un message : nature, et niveau pour une relance (maquette : « Relance 2 » en rouge). */
function kindPill(m: Pick<OutboxMessage, "kind" | "reminderLevel">): { tone: "info" | "lagon" | "bad" | "warn" | "neutral"; label: string } {
  if (m.kind === "RELANCE") {
    return m.reminderLevel === 0 ? { tone: "neutral", label: "Rappel" } : { tone: (m.reminderLevel ?? 1) >= 2 ? "bad" : "warn", label: `Relance ${m.reminderLevel ?? ""}`.trim() };
  }
  return m.kind === "FACTURE" ? { tone: "info", label: "Facture" } : { tone: "lagon", label: "Avoir" };
}

/**
 * Boîte d'envoi (maquette) : rien ne part sans votre clic. Chaque message se relit et se corrige, puis part seul ou
 * avec la sélection ; un envoi refusé par la messagerie reste ici avec l'erreur.
 */
export function OutboxPage() {
  const query = useOutbox();
  const [view, setView] = useState<"attente" | "historique">("attente");
  const [selected, setSelected] = useState<string | null>(null);
  const [checked, setChecked] = useState<Set<string>>(new Set());
  const send = useBillingWrite((ids: string[]) => billingApi.send(ids));
  const discard = useBillingWrite((input: { ids: string[]; reason: string | null }) => billingApi.discard(input.ids, input.reason));
  const prepare = useBillingWrite(() => billingApi.prepareReminders());

  const all = query.data?.messages ?? [];
  const messages = all.filter((m) => (view === "attente" ? m.status === "PREPARE" : m.status !== "PREPARE"));
  const current = messages.find((m) => m.id === selected) ?? messages[0] ?? null;
  useEffect(() => {
    setChecked((c) => new Set([...c].filter((id) => all.some((m) => m.id === id && m.status === "PREPARE"))));
  }, [query.data]); // eslint-disable-line react-hooks/exhaustive-deps

  const results = send.data;
  const sentCount = results?.filter((r) => r.sent).length ?? 0;
  const failures = results?.filter((r) => !r.sent) ?? [];

  return (
    <>
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="m-0 font-display text-display">Boîte d’envoi</h1>
          <p className="mt-1 text-muted-foreground">Rien ne part sans votre clic. Les relances sont préparées chaque jour ouvré à 06:00 ; les factures validées y préparent leur e-mail.</p>
        </div>
        {view === "attente" && (
          <div className="flex gap-2">
            <Button variant="ghost" disabled={prepare.isPending} onClick={() => prepare.mutate(undefined)}>
              Préparer les relances maintenant
            </Button>
            <Button variant="outline" disabled={checked.size === 0 || discard.isPending} onClick={() => discard.mutate({ ids: [...checked], reason: null })}>
              Écarter la sélection
            </Button>
            <Button disabled={checked.size === 0 || send.isPending} onClick={() => send.mutate([...checked])}>
              <Send aria-hidden />
              Envoyer la sélection ({checked.size})
            </Button>
          </div>
        )}
      </div>

      {results && (
        <div role="status" className={cn("rounded-md px-4 py-3", failures.length ? "bg-warning-100 text-warning-700" : "bg-success-100 text-success-700")}>
          {sentCount > 0 && `${sentCount} message${sentCount > 1 ? "s envoyés" : " envoyé"}. `}
          {failures.map((f) => f.error).filter(Boolean).join(" ")}
        </div>
      )}
      {prepare.data && (
        <p role="status" className="m-0 text-muted-foreground">
          {prepare.data.messages === 0 ? "Aucune relance due aujourd’hui." : `${prepare.data.messages} relance${prepare.data.messages > 1 ? "s préparées" : " préparée"}.`}
        </p>
      )}
      {(send.isError || discard.isError) && (
        <p role="alert" className="m-0 text-danger-700">
          {errorMessage(send.error ?? discard.error)}
        </p>
      )}

      <div role="tablist" aria-label="Messages" className="flex gap-1 border-b border-border">
        {(["attente", "historique"] as const).map((v) => (
          <button
            key={v}
            role="tab"
            type="button"
            aria-selected={view === v}
            onClick={() => {
              setView(v);
              setSelected(null);
            }}
            className={cn("border-b-2 border-transparent px-3.5 py-2.5 font-semibold text-muted-foreground", view === v && "border-lagon-600 text-lagon-600")}
          >
            {v === "attente" ? `En attente · ${query.data?.pending ?? 0}` : "Envoyés et écartés"}
          </button>
        ))}
      </div>

      <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.5fr)]">
        <section className="rounded-lg border border-border-subtle bg-card p-2 shadow-1">
          {query.isPending ? (
            <p className="m-0 p-4 text-muted-foreground">Chargement…</p>
          ) : messages.length === 0 ? (
            <p className="m-0 p-4 text-muted-foreground">{view === "attente" ? "Aucun message en attente." : "Rien d’envoyé pour l’instant."}</p>
          ) : (
            <ul role="listbox" aria-label="Messages préparés" className="m-0 flex list-none flex-col p-0">
              {messages.map((m) => (
                <li
                  key={m.id}
                  role="option"
                  aria-selected={current?.id === m.id}
                  onClick={() => setSelected(m.id)}
                  className={cn("grid cursor-pointer grid-cols-[auto_1fr_auto] items-start gap-3 rounded-md p-3 hover:bg-lagon-50", current?.id === m.id && "bg-lagon-100 hover:bg-lagon-100")}
                >
                  {m.status === "PREPARE" ? (
                    <input
                      type="checkbox"
                      aria-label={`Sélectionner ${m.subject}`}
                      checked={checked.has(m.id)}
                      onClick={(e) => e.stopPropagation()}
                      onChange={(e) => setChecked((c) => { const next = new Set(c); if (e.target.checked) { next.add(m.id); } else { next.delete(m.id); } return next; })}
                      className="mt-1"
                    />
                  ) : (
                    <span />
                  )}
                  <div className="min-w-0">
                    <div className="font-semibold">
                      {m.crecheName ?? m.clientName} · <Pill tone={kindPill(m).tone}>{kindPill(m).label}</Pill>
                    </div>
                    <div className="truncate text-small text-muted-foreground">{m.subject}</div>
                    {m.lastError && m.status === "PREPARE" && <div className="text-small text-danger-700">Échec : {m.lastError}</div>}
                  </div>
                  <span className="text-small text-muted-foreground">{shortStamp(m.sentAt ?? m.createdAt)}</span>
                </li>
              ))}
            </ul>
          )}
        </section>
        {current && <MessageEditor key={current.id} message={current} onSent={() => setSelected(null)} />}
      </div>
    </>
  );
}

function MessageEditor({ message, onSent }: { message: OutboxMessage; onSent: () => void }) {
  const [to, setTo] = useState(message.to ?? "");
  const [cc, setCc] = useState(message.cc ?? "");
  const [subject, setSubject] = useState(message.subject);
  const [body, setBody] = useState(message.body);
  const save = useBillingWrite(() => billingApi.editMessage(message.id, { to: to || null, cc: cc || null, subject, body }));
  const send = useBillingWrite(async () => {
    await billingApi.editMessage(message.id, { to: to || null, cc: cc || null, subject, body });
    const [result] = await billingApi.send([message.id]);
    if (!result.sent) {
      throw new Error(result.error ?? "Envoi impossible.");
    }
    return result;
  });
  const discard = useBillingWrite(() => billingApi.discard([message.id], null));
  const editable = message.status === "PREPARE";
  const dirty = to !== (message.to ?? "") || cc !== (message.cc ?? "") || subject !== message.subject || body !== message.body;
  const error = save.error ?? send.error ?? discard.error;

  return (
    <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="m-0 font-display text-title-2">{message.kind === "FACTURE" ? "Facture" : message.kind === "AVOIR" ? "Avoir" : message.subject.split(" — ")[0]}</h2>
        {message.kind === "RELANCE" && <Pill tone={kindPill(message).tone}>{kindPill(message).label}</Pill>}
        {message.invoiceNumber && message.kind !== "RELANCE" && <Pill tone="neutral">{message.invoiceNumber} · {xpf(message.invoiceTotal)}</Pill>}
        <span className="ml-auto text-small text-muted-foreground">
          {message.status === "ENVOYE"
            ? `Envoyé le ${formatDateTime(new Date(message.sentAt!))} par ${message.sentBy}`
            : message.status === "ECARTE"
              ? `Écarté le ${formatDateTime(new Date(message.discardedAt!))}${message.discardReason ? ` : ${message.discardReason}` : ""}`
              : `Préparé le ${formatDateTime(new Date(message.createdAt))}`}
        </span>
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="m-to">À</Label>
        <Input id="m-to" value={to} disabled={!editable} onChange={(e) => setTo(e.target.value)} placeholder="Adresse du contact de facturation" />
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="m-cc">Copie</Label>
        <Input id="m-cc" value={cc} disabled={!editable} onChange={(e) => setCc(e.target.value)} />
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="m-obj">Objet</Label>
        <Input id="m-obj" value={subject} disabled={!editable} onChange={(e) => setSubject(e.target.value)} />
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="m-txt">Message</Label>
        <textarea id="m-txt" rows={11} value={body} disabled={!editable} onChange={(e) => setBody(e.target.value)} className="w-full rounded-md border border-input bg-card px-3 py-2 leading-[22px]" />
      </div>
      <div className="flex flex-wrap items-center gap-2">
        {message.attachments.map((name, i) => (
          <span key={name} className="inline-flex items-center gap-2 rounded-md border border-border bg-muted px-3 py-1.5 text-label">
            <FileText aria-hidden className="size-4 text-danger-700" />
            <a href={`/api/v1/factures/${message.invoiceIds[i]}/pdf`} target="_blank" rel="noreferrer" className="hover:underline">
              {name}
            </a>
          </span>
        ))}
        {message.attachments.length === 0 && message.kind === "RELANCE" && <span className="text-small text-muted-foreground">Sans pièce jointe (règle du niveau).</span>}
        {message.invoiceIds.map((id, i) => (
          <Link key={id} to={`/factures?id=${id}`} className="text-small text-lagon-600 hover:underline">
            Voir {message.invoiceNumbers[i]}
          </Link>
        ))}
      </div>
      {message.kind === "RELANCE" && editable && (
        <p className="m-0 text-small text-muted-foreground">Au clic, les factures sont vérifiées : si l’une a été soldée entre-temps, la relance n’est pas envoyée et se retire.</p>
      )}
      {error && (
        <p role="alert" className="m-0 text-danger-700">
          {error instanceof Error ? error.message : errorMessage(error)}
        </p>
      )}
      {editable && (
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="ghost" disabled={discard.isPending} onClick={() => discard.mutate(undefined)}>
            Écarter
          </Button>
          <span className="flex-1" />
          <Button variant="outline" disabled={!dirty || save.isPending} onClick={() => save.mutate(undefined)}>
            Enregistrer
          </Button>
          <Button disabled={send.isPending || !to.trim()} onClick={() => send.mutate(undefined, { onSuccess: onSent })}>
            <Send aria-hidden />
            Envoyer
          </Button>
        </div>
      )}
    </section>
  );
}
