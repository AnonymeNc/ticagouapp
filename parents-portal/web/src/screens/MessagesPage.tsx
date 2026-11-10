import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { errorMessage, portal } from "../api";
import { Banner, Page, TouchButton, cn } from "../components";
import { dateFr, time, todayIso } from "../format";
import { meKey, useSession } from "../session";

/**
 * Messages avec la crèche (maquette « Messages avec la crèche »). Un fil par crèche ; l'équipe signe de son prénom.
 * Écart : l'onglet « Conversations » de la maquette n'apparaît que si la famille a des enfants dans deux crèches.
 */
export function MessagesPage() {
  const { me, family: current } = useSession();
  const queryClient = useQueryClient();
  const [structureId, setStructureId] = useState(current.structureId);
  const family = me.families.find((f) => f.structureId === structureId) ?? current;
  const thread = useQuery({
    queryKey: ["messages", family.structureId, family.familyId],
    queryFn: async ({ signal }) => {
      const list = await portal.messages(family.structureId, family.familyId, signal);
      void queryClient.invalidateQueries({ queryKey: meKey });
      return list;
    },
    refetchInterval: 60_000,
  });
  const [body, setBody] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);

  const send = async () => {
    setSending(true);
    setError(null);
    try {
      await portal.send(family.structureId, family.familyId, body.trim());
      setBody("");
      await thread.refetch();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setSending(false);
    }
  };

  let lastDay = "";
  return (
    <Page title="Messages">
      {me.families.length > 1 && (
        <div role="tablist" aria-label="Conversations" className="flex max-w-full gap-1 overflow-x-auto rounded-full bg-sunken p-1">
          {me.families.map((f) => (
            <button key={f.structureId} role="tab" type="button" aria-selected={f.structureId === family.structureId}
              onClick={() => setStructureId(f.structureId)}
              className={cn("min-h-11 shrink-0 grow whitespace-nowrap rounded-full px-4 text-[16px] font-semibold", f.structureId === family.structureId && "bg-surface text-lagon-700 shadow-[var(--shadow-1)]")}>
              {f.structureName}
            </button>
          ))}
        </div>
      )}
      <section aria-label={`Conversation avec ${family.structureName}`} className="flex grow flex-col gap-3">
        {(thread.data ?? []).length === 0 && !thread.isPending && (
          <p className="m-0 text-ink-muted">Pas encore de message. Écrivez à l’équipe ci-dessous.</p>
        )}
        {(thread.data ?? []).map((m) => {
          const day = m.data.sentAt.slice(0, 10);
          const separator = day !== lastDay ? (day === todayIso() ? "Aujourd’hui" : dateFr(day)) : null;
          lastDay = day;
          return (
            <div key={m.id} className="flex flex-col gap-2">
              {separator && <p className="m-0 text-center text-[14px] font-semibold text-ink-muted">{separator}</p>}
              {m.data.fromFamily ? (
                <div className="flex max-w-[85%] flex-col gap-1 self-end rounded-lg rounded-br-sm bg-lagon-600 px-4 py-2.5 text-white">
                  <p className="m-0 whitespace-pre-wrap text-[17px]">{m.data.body}</p>
                  <span className="tabular text-[14px] text-white/80">Vous · {time(m.data.sentAt)}</span>
                </div>
              ) : (
                <div className="flex max-w-[85%] flex-col gap-1 self-start rounded-lg rounded-bl-sm border border-border-subtle bg-surface px-4 py-2.5">
                  <span className="text-[14px] font-semibold text-lagon-700">{m.data.author}</span>
                  <p className="m-0 whitespace-pre-wrap text-[17px]">{m.data.body}</p>
                  <span className="tabular text-[14px] text-ink-muted">{time(m.data.sentAt)}</span>
                </div>
              )}
            </div>
          );
        })}
        <p className="m-0 text-[15px] text-ink-muted">
          L’équipe répond pendant les heures d’ouverture. En cas d’urgence,{" "}
          {family.structurePhone ? <a href={`tel:${family.structurePhone.replace(/\s/g, "")}`}>appelez la crèche</a> : "appelez la crèche"}.
        </p>
      </section>
      <form className="flex flex-col gap-3" onSubmit={(e) => { e.preventDefault(); void send(); }}>
        <label htmlFor="message" className="text-[16px] font-semibold">Écrire à l’équipe</label>
        <textarea id="message" rows={3} maxLength={2000} value={body} onChange={(e) => setBody(e.target.value)} placeholder="Votre message"
          className="rounded-md border border-border-control bg-surface p-3 text-[17px]" />
        {error && <Banner tone="danger">{error}</Banner>}
        <TouchButton type="submit" disabled={sending || !body.trim()}>Envoyer</TouchButton>
      </form>
    </Page>
  );
}
