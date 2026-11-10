import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useLocation } from "react-router";
import { CircleHelp, Mail, MessageCircle, Phone } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { api } from "@/lib/api";

type Support = { phone: string | null; whatsapp: string | null; email: string | null; hours: string | null; delay: string | null };

/** Aide de chaque écran : quelques repères, dans l'ordre où on en a besoin. Le préfixe le plus long l'emporte. */
const HELP: { prefix: string; title: string; tips: string[] }[] = [
  { prefix: "/", title: "Tableau de bord", tips: [
    "Les alertes du jour sont rangées par gravité : encadrement, personne non autorisée, allergies, impayés.",
    "Les absences signalées et les demandes des parents se traitent directement depuis leurs cartes.",
    "La liste « Premiers pas » indique ce qui reste à mettre en place ; elle disparaît une fois tout fait." ] },
  { prefix: "/presences", title: "Présences", tips: [
    "Le pointage se fait sur la tablette de l’accueil ; ici, vous corrigez et validez les passages à vérifier.",
    "Une correction est tracée avec son motif et son auteur." ] },
  { prefix: "/enfants", title: "Enfants", tips: [
    "La fiche réunit santé (allergies, PAI, régime), personnes autorisées, autorisations et documents.",
    "Chaque consultation d’une fiche est inscrite au journal, comme l’exige la protection des données de santé." ] },
  { prefix: "/inscriptions", title: "Inscriptions", tips: [
    "Les demandes sont classées par étape ; les critères de priorité donnent un score.",
    "Le contrat d’accueil se calcule d’après le barème des tarifs : saisissez-le d’abord (Paramètres › Barème et facturation).",
    "Les demandes « En ligne » viennent du formulaire public (Paramètres › Pré-inscription en ligne)." ] },
  { prefix: "/facturation", title: "Facturation", tips: [
    "Préparez le mois, vérifiez les anomalies, puis validez : une facture validée ne se modifie plus, elle se corrige par avoir.",
    "Les montants sont en francs, sans décimale ; les aides apparaissent en déduction." ] },
  { prefix: "/statistiques", title: "Statistiques", tips: ["Les indicateurs se lisent par mois ; les exports servent au comptable."] },
  { prefix: "/repas", title: "Repas", tips: [
    "La vue cuisine liste les repas du jour et les enfants à adapter (allergies, régime).",
    "Températures : notez les relevés ; hors seuil, la mesure corrective est obligatoire." ] },
  { prefix: "/personnel", title: "Personnel", tips: [
    "Le planning de la semaine montre l’encadrement prévu et les créneaux en sous-effectif.",
    "Mon espace : demandes de congé et d’heures, compteurs, cahier d’équipe, agenda sur le téléphone." ] },
  { prefix: "/messages", title: "Messages et actualités", tips: [
    "Un fil par famille ; « Écrire à plusieurs familles » envoie le même message à une section ou à toutes.",
    "Les actualités apparaissent en tête de l’application des parents, avec leur pièce jointe." ] },
  { prefix: "/groupe", title: "Vue groupe", tips: ["Rien n’est partagé sans le consentement de la direction ; vous pouvez quitter le groupe à tout moment."] },
  { prefix: "/parametres", title: "Paramètres", tips: [
    "Chaque rubrique a ses règles datées : une modification vaut à partir d’une date, l’historique reste.",
    "Les droits de chaque rôle se lisent dans Utilisateurs et rôles." ] },
];

export function helpFor(pathname: string) {
  return HELP.filter((h) => h.prefix === "/" ? pathname === "/" : pathname === h.prefix || pathname.startsWith(`${h.prefix}/`))
    .sort((a, b) => b.prefix.length - a.prefix.length)[0] ?? HELP[0];
}

/**
 * Bouton « ? » de la barre du haut (lot 9) : l'aide de l'écran ouvert et les coordonnées du support, avec ses horaires
 * et son délai de réponse (réglés par l'hébergeur). Écart : pas de maquette dédiée.
 */
export function HelpButton() {
  const [open, setOpen] = useState(false);
  const location = useLocation();
  const support = useQuery({ queryKey: ["support"], queryFn: ({ signal }) => api<Support>("/support", { signal }), staleTime: 60 * 60_000, enabled: open });
  const help = helpFor(location.pathname);
  const s = support.data;
  const hasSupport = s && (s.phone || s.whatsapp || s.email);
  return (
    <>
      <Button variant="ghost" size="icon-sm" aria-label="Aide" title="Aide" onClick={() => setOpen(true)}>
        <CircleHelp aria-hidden />
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Aide · {help.title}</DialogTitle>
            <DialogDescription>Les repères de cet écran.</DialogDescription>
          </DialogHeader>
          <ul className="m-0 flex list-disc flex-col gap-2 pl-5">
            {help.tips.map((t) => <li key={t}>{t}</li>)}
          </ul>
          <section aria-labelledby="support" className="flex flex-col gap-2 rounded-md bg-muted p-4">
            <h3 id="support" className="m-0 text-label">Besoin d’aide ? Le support Ti’Cagou</h3>
            {!hasSupport && <p className="m-0 text-muted-foreground">Contactez votre interlocuteur Ti’Cagou habituel.</p>}
            {s?.phone && <a href={`tel:${s.phone.replace(/\s/g, "")}`} className="inline-flex items-center gap-2"><Phone aria-hidden className="size-4" />{s.phone}</a>}
            {s?.whatsapp && (
              <a href={`https://wa.me/${s.whatsapp.replace(/\D/g, "")}`} target="_blank" rel="noreferrer" className="inline-flex items-center gap-2">
                <MessageCircle aria-hidden className="size-4" />WhatsApp {s.whatsapp}
              </a>
            )}
            {s?.email && <a href={`mailto:${s.email}`} className="inline-flex items-center gap-2"><Mail aria-hidden className="size-4" />{s.email}</a>}
            {s?.hours && <p className="m-0 text-small text-muted-foreground">{s.hours}</p>}
            {s?.delay && <p className="m-0 text-small text-muted-foreground">{s.delay}</p>}
          </section>
        </DialogContent>
      </Dialog>
    </>
  );
}
