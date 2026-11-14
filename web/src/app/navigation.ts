import {
  Building2,
  ChartColumn,
  ClipboardCheck,
  ClipboardList,
  IdCard,
  LayoutDashboard,
  MessagesSquare,
  Receipt,
  Settings,
  Users,
  UtensilsCrossed,
  type LucideIcon,
} from "lucide-react";

import type { Role } from "@/features/auth/types";
import type { Rubrique } from "@/features/structure/useRubriques";

export type NavItem = {
  path: string;
  label: string;
  icon: LucideIcon;
  /** Rôles qui voient l’entrée ; absent = tous (maquette Paramètres › Droits par rôle). */
  roles?: Role[];
  /** Rubrique masquable à la demande de la crèche (ADR 0022). */
  rubrique?: Rubrique;
};

/** Navigation principale du poste bureau, dans l'ordre des maquettes. */
export const navItems: NavItem[] = [
  { path: "/", label: "Tableau de bord", icon: LayoutDashboard },
  { path: "/presences", label: "Présences", icon: ClipboardCheck },
  { path: "/enfants", label: "Enfants", icon: Users, roles: ["DIRECTION", "EDUCATRICE", "CUISINE"] },
  { path: "/inscriptions", label: "Inscriptions", icon: ClipboardList, roles: ["DIRECTION", "COMPTABILITE"], rubrique: "INSCRIPTIONS" },
  { path: "/facturation", label: "Facturation", icon: Receipt, roles: ["DIRECTION", "COMPTABILITE"], rubrique: "FACTURATION" },
  { path: "/statistiques", label: "Statistiques", icon: ChartColumn, roles: ["DIRECTION", "COMPTABILITE"], rubrique: "FACTURATION" },
  { path: "/repas", label: "Repas", icon: UtensilsCrossed, roles: ["DIRECTION", "EDUCATRICE", "CUISINE"], rubrique: "REPAS" },
  { path: "/personnel", label: "Personnel", icon: IdCard, rubrique: "PERSONNEL" },
  { path: "/groupe", label: "Vue groupe", icon: Building2, roles: ["DIRECTION"] },
  { path: "/messages", label: "Messages", icon: MessagesSquare, roles: ["DIRECTION", "EDUCATRICE"], rubrique: "PORTAIL_PARENTS" },
  { path: "/parametres", label: "Paramètres", icon: Settings, roles: ["DIRECTION", "COMPTABILITE"] },
];

/**
 * Barre basse du téléphone (maquette « Direction mobile », moins de 768 px) : les gestes du quotidien selon le rôle,
 * dans l’ordre de préférence ; les entrées masquées (rôle, rubrique) sont sautées. Le reste est dans « Plus ».
 */
export const bottomTabsByRole: Record<Role, string[]> = {
  DIRECTION: ["/", "/presences", "/enfants", "/messages", "/repas"],
  EDUCATRICE: ["/", "/presences", "/enfants", "/messages", "/repas"],
  COMPTABILITE: ["/", "/facturation", "/statistiques", "/inscriptions", "/parametres"],
  CUISINE: ["/", "/repas", "/enfants", "/presences"],
};

/** Nombre d’entrées de la barre basse, « Plus » non compris. */
export const BOTTOM_TABS = 4;

/** Entrées visibles pour ce rôle et ces rubriques, réparties entre la barre basse et « Plus ». */
export function splitNavigation(role: Role, shown: (rubrique: Rubrique | undefined) => boolean) {
  const visible = navItems.filter((item) => (!item.roles || item.roles.includes(role)) && shown(item.rubrique));
  const tabs = bottomTabsByRole[role]
    .map((path) => visible.find((item) => item.path === path))
    .filter((item): item is NavItem => item !== undefined)
    .slice(0, BOTTOM_TABS);
  const more = visible.filter((item) => !tabs.includes(item));
  return { visible, tabs, more };
}

/** Libellés courts de la barre basse. */
export const shortLabels: Record<string, string> = {
  "/": "Accueil",
  "/statistiques": "Stats",
};
