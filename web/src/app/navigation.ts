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
import type { LicenseModule } from "@/features/license/useModules";

export type NavItem = {
  path: string;
  label: string;
  icon: LucideIcon;
  /** Rôles qui voient l’entrée ; absent = tous (maquette Paramètres › Droits par rôle). */
  roles?: Role[];
  /** Module de l'abonnement ; l’entrée est masquée s’il n’est pas compris. */
  module?: LicenseModule;
};

/** Navigation principale du poste bureau, dans l'ordre des maquettes. */
export const navItems: NavItem[] = [
  { path: "/", label: "Tableau de bord", icon: LayoutDashboard },
  { path: "/presences", label: "Présences", icon: ClipboardCheck },
  { path: "/enfants", label: "Enfants", icon: Users, roles: ["DIRECTION", "EDUCATRICE", "CUISINE"] },
  { path: "/inscriptions", label: "Inscriptions", icon: ClipboardList, roles: ["DIRECTION", "COMPTABILITE"], module: "INSCRIPTIONS" },
  { path: "/facturation", label: "Facturation", icon: Receipt, roles: ["DIRECTION", "COMPTABILITE"], module: "FACTURATION" },
  { path: "/statistiques", label: "Statistiques", icon: ChartColumn, roles: ["DIRECTION", "COMPTABILITE"], module: "FACTURATION" },
  { path: "/repas", label: "Repas", icon: UtensilsCrossed, roles: ["DIRECTION", "EDUCATRICE", "CUISINE"], module: "REPAS" },
  { path: "/personnel", label: "Personnel", icon: IdCard, module: "PERSONNEL" },
  { path: "/groupe", label: "Vue groupe", icon: Building2, roles: ["DIRECTION"] },
  { path: "/messages", label: "Messages", icon: MessagesSquare, roles: ["DIRECTION", "EDUCATRICE"], module: "PORTAIL_PARENTS" },
  { path: "/parametres", label: "Paramètres", icon: Settings, roles: ["DIRECTION", "COMPTABILITE"] },
];
