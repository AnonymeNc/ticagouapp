export type Role = "DIRECTION" | "EDUCATRICE" | "CUISINE" | "COMPTABILITE";

export const roleLabels: Record<Role, string> = {
  DIRECTION: "Direction",
  EDUCATRICE: "Éducatrice",
  CUISINE: "Cuisine",
  COMPTABILITE: "Comptabilité",
};

export type Me = {
  id: string;
  structureId: string;
  firstName: string;
  lastName: string;
  role: Role;
  authMethod: "PASSWORD" | "PIN";
  lastLoginAt: string | null;
  mfaEnabled: boolean;
  /** Double authentification à enregistrer avant tout accès (direction, comptabilité). */
  mfaEnrollmentRequired: boolean;
};

export type SessionResponse = {
  accessToken: string;
  expiresIn: number;
  /** Absent au bureau : le jeton est dans un cookie HttpOnly. */
  refreshToken: string | null;
  /** Session PIN : preuve de l'auteur des pointages envoyés plus tard par la tablette. */
  sessionId?: string;
  user: Me;
};

export type PinUser = {
  id: string;
  displayName: string;
  initials: string;
  role: Role;
};
