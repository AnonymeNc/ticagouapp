import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router";
import { ArrowLeft, FileText, Megaphone, Paperclip } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { useSections } from "@/features/structure/useSections";
import { errorMessage } from "@/lib/api";
import { formatDate, isoToDateFr, parseDateFr } from "@/lib/datetime";
import { selectClass } from "@/lib/formFields";
import { portalApi, portalKeys, type News } from "./api";

const statusLabels: Record<News["status"], string> = { PUBLIEE: "Publiée", EXPIREE: "Expirée", RETIREE: "Retirée" };

/**
 * Actualités de la crèche publiées sur l'application des parents (lot 1) : fête, sortie, menu de la semaine, projet
 * pédagogique. Toute la crèche ou une section ; une pièce jointe (PDF ou image) apparaît aussi dans la rubrique
 * « De la crèche » des parents. Écart : pas de maquette bureau pour cet écran.
 */
export function NewsPage() {
  const queryClient = useQueryClient();
  const news = useQuery({ queryKey: portalKeys.news, queryFn: ({ signal }) => portalApi.news(signal) });
  const sections = useSections();
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [sectionId, setSectionId] = useState("");
  const [expires, setExpires] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [fileKey, setFileKey] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const publish = async () => {
    setError(null);
    setDone(null);
    if (!title.trim() || !body.trim()) return setError("Donnez un titre et un texte à l’actualité.");
    const expiresOn = expires.trim() ? parseDateFr(expires) : null;
    if (expires.trim() && !expiresOn) return setError("Saisissez la date de fin au format JJ/MM/AAAA.");
    if (file && file.size > 5 * 1024 * 1024) return setError("La pièce jointe doit peser au plus 5 Mo.");
    setBusy(true);
    try {
      const saved = await portalApi.publishNews({ title: title.trim(), body: body.trim(), sectionId: sectionId || null, expiresOn, file });
      setTitle("");
      setBody("");
      setExpires("");
      setFile(null);
      setFileKey((k) => k + 1);
      setDone(saved.families > 0
        ? `Publiée : ${saved.families} famille${saved.families > 1 ? "s la verront" : " la verra"} à la prochaine synchronisation.`
        : "Publiée, mais aucune famille invitée au portail ne la verra pour le moment.");
      await queryClient.invalidateQueries({ queryKey: portalKeys.news });
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const withdraw = async (n: News) => {
    if (!window.confirm(`Retirer « ${n.title} » ? Elle disparaîtra de l’application des parents.`)) return;
    try {
      await portalApi.withdrawNews(n.id);
      await queryClient.invalidateQueries({ queryKey: portalKeys.news });
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const open = async (n: News) => {
    try {
      const { blob } = await portalApi.newsFile(n.id);
      window.open(URL.createObjectURL(blob), "_blank", "noopener");
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <>
      <div className="flex flex-col gap-1">
        <Link to="/messages" className="inline-flex items-center gap-1 self-start text-lagon-700"><ArrowLeft aria-hidden className="size-4" />Messages</Link>
        <h1 className="m-0 font-display text-display">Actualités de la crèche</h1>
        <p className="m-0 text-muted-foreground">Ce que les parents lisent en ouvrant l’application : fêtes, sorties, menus, informations.</p>
      </div>

      <div className="grid gap-4 lg:grid-cols-[minmax(0,420px)_1fr]">
        <form aria-label="Nouvelle actualité" className="flex flex-col gap-3 self-start rounded-lg border border-border-subtle bg-card p-5 shadow-1"
          onSubmit={(e) => { e.preventDefault(); void publish(); }}>
          <h2 className="m-0 text-title-2">Nouvelle actualité</h2>
          <FormField label="Titre"><Input value={title} maxLength={120} onChange={(e) => setTitle(e.target.value)} placeholder="Fête de fin d’année" /></FormField>
          <FormField label="Texte"><Textarea value={body} maxLength={2000} rows={5} onChange={(e) => setBody(e.target.value)} /></FormField>
          <FormField label="Pour">
            <select className={selectClass} value={sectionId} onChange={(e) => setSectionId(e.target.value)}>
              <option value="">Toute la crèche</option>
              {(sections.data ?? []).map((s) => <option key={s.id} value={s.id}>Section des {s.name}</option>)}
            </select>
          </FormField>
          <FormField label="Affichée jusqu’au" hint="Facultatif, JJ/MM/AAAA. Sans date : 12 mois au plus.">
            <Input value={expires} inputMode="numeric" placeholder={isoToDateFr("2026-12-19")} onChange={(e) => setExpires(e.target.value)} />
          </FormField>
          <FormField label="Pièce jointe" hint="Facultatif : menu, projet pédagogique, affiche. PDF, JPEG ou PNG, 5 Mo au plus.">
            <Input key={fileKey} type="file" accept="application/pdf,image/jpeg,image/png" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          </FormField>
          {error && <Alert>{error}</Alert>}
          {done && <p role="status" className="m-0 rounded-md bg-success-100 px-3 py-2 text-success-700">{done}</p>}
          <Button type="submit" disabled={busy}><Megaphone aria-hidden />Publier pour les parents</Button>
        </form>

        <section aria-label="Actualités publiées" className="flex flex-col gap-3">
          {(news.data ?? []).length === 0 && (
            <p className="m-0 text-muted-foreground">{news.isPending ? "Chargement…" : "Aucune actualité publiée pour le moment."}</p>
          )}
          {(news.data ?? []).map((n) => (
            <article key={n.id} className="flex flex-col gap-2 rounded-lg border border-border-subtle bg-card p-4 shadow-1">
              <div className="flex flex-wrap items-center gap-2">
                <h3 className="m-0 grow text-title-3">{n.title}</h3>
                <Badge variant={n.status === "PUBLIEE" ? "success" : "secondary"}>{statusLabels[n.status]}</Badge>
              </div>
              <p className="m-0 text-small text-muted-foreground">
                {n.sectionName ? `Section des ${n.sectionName}` : "Toute la crèche"} · {n.publishedBy} · {formatDate(new Date(n.publishedAt))}
                {n.expiresOn && ` · jusqu’au ${isoToDateFr(n.expiresOn)}`}
                {n.status === "PUBLIEE" && ` · ${n.families} famille${n.families > 1 ? "s" : ""}`}
              </p>
              <p className="m-0 whitespace-pre-wrap">{n.body}</p>
              <div className="flex flex-wrap gap-2">
                {n.fileName && (
                  <Button variant="secondary" size="sm" onClick={() => void open(n)}>
                    {n.fileName.toLowerCase().endsWith(".pdf") ? <FileText aria-hidden /> : <Paperclip aria-hidden />}{n.fileName}
                  </Button>
                )}
                {n.status === "PUBLIEE" && <Button variant="ghost" size="sm" onClick={() => void withdraw(n)}>Retirer</Button>}
              </div>
            </article>
          ))}
        </section>
      </div>
    </>
  );
}
