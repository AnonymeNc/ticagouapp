import { ChartColumn, LayoutDashboard, Receipt, Send, Server, Settings, SquareTerminal, type LucideIcon } from "lucide-react";

export type NavItem = {
  path: string;
  label: string;
  icon: LucideIcon;
  /** Phase du plan (docs/plan-console-editeur.md) qui livre l'écran. */
  phase: number;
  description: string;
};

/** Navigation de la console, dans l'ordre de la maquette. */
export const navItems: NavItem[] = [
  { path: "/", label: "Tableau de bord", icon: LayoutDashboard, phase: 2, description: "Ce qui demande votre attention aujourd’hui." },
  { path: "/parc", label: "Parc des crèches", icon: Server, phase: 2, description: "Santé, version, abonnement et usage de chaque crèche." },
  { path: "/factures", label: "Factures", icon: Receipt, phase: 3, description: "Abonnements, prestations, avoirs et paiements." },
  { path: "/envoi", label: "Boîte d’envoi", icon: Send, phase: 4, description: "Factures et relances préparées : rien ne part sans votre clic." },
  { path: "/ordres", label: "Ordres", icon: SquareTerminal, phase: 5, description: "Abonnements, suspensions et mises à jour exécutés par creche.sh appliquer." },
  { path: "/reporting", label: "Reporting", icon: ChartColumn, phase: 6, description: "Chiffre d’affaires, encours, balance âgée, TGC et exports." },
  { path: "/parametres", label: "Paramètres", icon: Settings, phase: 1, description: "Barème, règles de relance, mentions de l’éditeur, comptes et journal." },
];
