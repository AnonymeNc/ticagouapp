import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router";
import { Megaphone, MessagesSquare, Send, Users } from "lucide-react";
import { FormField } from "@/components/FormField";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { useSections } from "@/features/structure/useSections";
import { selectClass } from "@/lib/formFields";
import { Alert } from "@/components/FocusLayout";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/api";
import { formatDate, formatTime } from "@/lib/datetime";
import { cn } from "@/lib/utils";
import { portalApi, portalKeys } from "./api";

const stamp = (iso: string) => `${formatDate(new Date(iso))} · ${formatTime(new Date(iso))}`;

/**
 * Messages avec les familles (portail parents) : un fil par famille, les messages des parents arrivent à chaque
 * synchronisation. Écart : pas de maquette bureau pour cet écran.
 */
export function MessagesPage() {
  const queryClient = useQueryClient();
  const threads = useQuery({ queryKey: portalKeys.threads, queryFn: ({ signal }) => portalApi.threads(signal), refetchInterval: 60_000 });
  const [familyId, setFamilyId] = useState<string | null>(null);
  const thread = useQuery({
    queryKey: portalKeys.thread(familyId ?? ""),
    queryFn: ({ signal }) => portalApi.thread(familyId!, signal),
    enabled: familyId !== null,
  });
  const [body, setBody] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [broadcasting, setBroadcasting] = useState(false);
  const [sent, setSent] = useState<string | null>(null);
  const isDirection = useCurrentUser().role === "DIRECTION";

  const open = async (id: string) => {
    setFamilyId(id);
    setError(null);
    // Le fil marque les messages comme lus : la pastille du menu se met à jour.
    setTimeout(() => void queryClient.invalidateQueries({ queryKey: portalKeys.threads }), 500);
  };

  const send = async () => {
    if (!familyId || !body.trim()) return;
    setError(null);
    try {
      await portalApi.send(familyId, body.trim());
      setBody("");
      await queryClient.invalidateQueries({ queryKey: ["messages"] });
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <>
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="m-0 grow font-display text-display">Messages des familles</h1>
        {isDirection && (
          <>
            <Button variant="outline" asChild><Link to="/messages/actualites"><Megaphone aria-hidden />Actualités de la crèche</Link></Button>
            <Button onClick={() => { setSent(null); setBroadcasting(true); }}><Users aria-hidden />Écrire à plusieurs familles</Button>
          </>
        )}
      </div>
      {sent && <p role="status" className="m-0 rounded-md bg-success-100 px-3 py-2 text-success-700">{sent}</p>}
      {broadcasting && (
        <BroadcastDialog onClose={() => setBroadcasting(false)} onSent={async (families) => {
          setBroadcasting(false);
          setSent(`Message envoyé à ${families} famille${families > 1 ? "s" : ""} : il est dans le fil de chacune.`);
          await queryClient.invalidateQueries({ queryKey: ["messages"] });
        }} />
      )}
      <div className="grid min-h-[60vh] gap-4 md:grid-cols-[320px_1fr]">
        <nav aria-label="Familles" className="flex flex-col gap-1 rounded-lg border border-border-subtle bg-card p-2 shadow-1">
          {(threads.data ?? []).length === 0 && (
            <p className="m-0 p-3 text-muted-foreground">Aucune famille invitée au portail pour le moment.</p>
          )}
          {(threads.data ?? []).map((t) => (
            <button key={t.familyId} type="button" onClick={() => void open(t.familyId)}
              aria-current={familyId === t.familyId ? "page" : undefined}
              className={cn("flex flex-col rounded-md px-3 py-2 text-left", familyId === t.familyId ? "bg-lagon-50 text-lagon-700" : "hover:bg-muted")}>
              <span className="flex items-center gap-2 font-semibold">
                <span className="grow">{t.familyLabel}</span>
                {t.unread > 0 && <span className="rounded-full bg-lagon-600 px-2 text-caption text-white">{t.unread}</span>}
              </span>
              <span className="truncate text-small text-muted-foreground">{t.lastBody ?? (t.portalAccess ? "Pas encore de message" : "Pas d’accès au portail")}</span>
            </button>
          ))}
        </nav>
        <section aria-label="Fil de la famille" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
          {!thread.data ? (
            <p className="m-0 flex items-center gap-2 text-muted-foreground"><MessagesSquare aria-hidden className="size-5" />Choisissez une famille.</p>
          ) : (
            <>
              <h2 className="m-0 text-title-2">{thread.data.familyLabel}</h2>
              <ol className="m-0 flex grow list-none flex-col gap-2 p-0">
                {thread.data.messages.map((m) => (
                  <li key={m.id} className={cn("max-w-[75%] rounded-lg px-4 py-2",
                    m.direction === "EQUIPE" ? "self-end bg-lagon-100" : "self-start bg-muted")}>
                    <p className="m-0 whitespace-pre-wrap">{m.body}</p>
                    <p className="m-0 text-small text-muted-foreground">{m.authorName} · {stamp(m.sentAt)}</p>
                  </li>
                ))}
              </ol>
              {thread.data.portalAccess ? (
                <form className="flex items-end gap-2" onSubmit={(e) => { e.preventDefault(); void send(); }}>
                  <Textarea value={body} maxLength={2000} rows={2} onChange={(e) => setBody(e.target.value)}
                    aria-label="Votre message" placeholder="Écrire à la famille…" className="grow" />
                  <Button type="submit" disabled={!body.trim()}><Send aria-hidden />Envoyer</Button>
                </form>
              ) : (
                <p className="m-0 text-muted-foreground">Invitez un responsable au portail (fiche enfant › Journée et portail) pour lui écrire.</p>
              )}
            </>
          )}
          {error && <Alert>{error}</Alert>}
        </section>
      </div>
    </>
  );
}

/** Message à toutes les familles invitées ou à celles d'une section : un message dans le fil de chacune. */
function BroadcastDialog({ onClose, onSent }: { onClose: () => void; onSent: (families: number) => void }) {
  const sections = useSections();
  const [sectionId, setSectionId] = useState("");
  const [body, setBody] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    if (!body.trim()) return setError("Écrivez votre message.");
    setBusy(true);
    setError(null);
    try {
      const result = await portalApi.broadcast(sectionId || null, body.trim());
      onSent(result.families);
    } catch (e) {
      setError(errorMessage(e));
      setBusy(false);
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Écrire à plusieurs familles</DialogTitle>
          <DialogDescription>Chaque famille invitée au portail reçoit le message dans son fil et peut y répondre.</DialogDescription>
        </DialogHeader>
        <form className="flex flex-col gap-3" onSubmit={(e) => { e.preventDefault(); void submit(); }}>
          <FormField label="Destinataires">
            <select className={selectClass} value={sectionId} onChange={(e) => setSectionId(e.target.value)}>
              <option value="">Toutes les familles</option>
              {(sections.data ?? []).map((s) => <option key={s.id} value={s.id}>Familles de la section des {s.name}</option>)}
            </select>
          </FormField>
          <FormField label="Message">
            <Textarea value={body} maxLength={2000} rows={5} onChange={(e) => setBody(e.target.value)} placeholder="Pensez aux chapeaux demain : sortie au parc." />
          </FormField>
          {error && <Alert>{error}</Alert>}
          <Button type="submit" disabled={busy || !body.trim()}><Send aria-hidden />Envoyer</Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}
