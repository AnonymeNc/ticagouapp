/** Types et appels du module Enfants (miroir des DTO serveur, en attendant le client généré). */
import { api, apiBlob, apiUpload, saveBlob } from "@/lib/api";

export type Severity = "LEGERE" | "MODEREE" | "SEVERE";
export type ConsentType = "PHOTOS" | "SORTIES" | "SOINS_URGENCE";
export type DocumentCategory = "CONTRAT" | "SANTE" | "AUTORISATIONS" | "FAMILLE" | "AUTRE";

export const severityLabels: Record<Severity, string> = { LEGERE: "Légère", MODEREE: "Modérée", SEVERE: "Sévère" };
export const consentLabels: Record<ConsentType, string> = {
  PHOTOS: "Photos et vidéos",
  SORTIES: "Sorties",
  SOINS_URGENCE: "Soins d’urgence",
};
export const categoryLabels: Record<DocumentCategory, string> = {
  CONTRAT: "Contrat",
  SANTE: "Santé",
  AUTORISATIONS: "Autorisations",
  FAMILLE: "Famille",
  AUTRE: "Autre",
};

export type AllergySummary = { allergen: string; severity: Severity };
export type HealthSummary = { allergies: AllergySummary[]; pai: boolean; diet: string | null };

export type ChildListItem = {
  id: string;
  fileNumber: string;
  firstName: string;
  lastName: string;
  birthDate: string;
  sectionId: string | null;
  entryDate: string | null;
  exitDate: string | null;
  hasPhoto: boolean;
  familyId: string;
  health: HealthSummary;
};

export type Guardian = {
  id: string;
  firstName: string;
  lastName: string;
  relationship: string;
  legalGuardian: boolean;
  payer: boolean;
  mobilePhone: string | null;
  workPhone: string | null;
  email: string | null;
  employer: string | null;
  displayOrder: number;
};

export type Family = {
  id: string;
  label: string;
  address: string | null;
  postalCode: string | null;
  city: string | null;
  situation: string | null;
  notes: string | null;
  version: number;
  guardians: Guardian[];
  children: { id: string; firstName: string; lastName: string; birthDate: string; exitDate: string | null }[];
};

export type AuthorizedPerson = {
  id: string;
  firstName: string;
  lastName: string;
  relationship: string;
  phone: string | null;
  idVerifiedOn: string | null;
  idVerifiedBy: string | null;
  hasPhoto: boolean;
};

export type Allergy = { allergen: string; reaction: string | null; severity: Severity };
export type Vaccination = { vaccine: string; lastDoseOn: string | null };

export type Health = {
  diet: string | null;
  dietDetails: string | null;
  paiActive: boolean;
  paiReason: string | null;
  paiValidFrom: string | null;
  paiValidUntil: string | null;
  paiKitLocation: string | null;
  paiKitDetails: string | null;
  paiDocumentId: string | null;
  emergencyProtocol: string | null;
  doctorName: string | null;
  doctorPhone: string | null;
  doctorAddress: string | null;
  instructions: string | null;
  vaccinationCheckedOn: string | null;
  allergies: Allergy[];
  vaccinations: Vaccination[];
  /** Régime de la liste (Paramètres › Repas et régimes) ; `diet` en garde le libellé. */
  dietId: string | null;
  mealTexture: "NORMALE" | "HACHEE" | "MIXEE";
  /** Panier repas fourni par la famille (PAI) : ni repas ni goûter préparés par la cuisine. */
  packedLunch: boolean;
};

export type Consent = {
  type: ConsentType;
  granted: boolean;
  details: string | null;
  signedOn: string | null;
  signedBy: string | null;
};

export type ChildDocument = {
  id: string;
  name: string;
  fileName: string;
  category: DocumentCategory;
  validUntil: string | null;
  createdAt: string;
  createdBy: string;
  contentType: string;
  sizeBytes: number;
};

export type ChildRecord = {
  id: string;
  fileNumber: string;
  firstName: string;
  lastName: string;
  birthDate: string;
  sectionId: string | null;
  entryDate: string | null;
  exitDate: string | null;
  homeLanguages: string | null;
  hasPhoto: boolean;
  version: number;
  family: Family;
  authorizedPersons: AuthorizedPerson[];
  health: Health;
  consents: Consent[];
  documents: ChildDocument[];
};

export type ChildIdentity = {
  firstName: string;
  lastName: string;
  birthDate: string;
  sectionId: string | null;
  entryDate: string | null;
  exitDate: string | null;
  homeLanguages: string | null;
};

export type GuardianInput = Omit<Guardian, "id">;
export type FamilyInput = Pick<Family, "label" | "address" | "postalCode" | "city" | "situation" | "notes">;
export type FamilySummary = { id: string; label: string; city: string | null; guardianNames: string[] };

export const childKeys = {
  list: (filters: object) => ["children", filters] as const,
  record: (id: string) => ["child", id] as const,
};

export const childrenApi = {
  list: (params: { q?: string; sectionId?: string; withAllergy?: boolean; includeExited?: boolean }, signal?: AbortSignal) => {
    const query = new URLSearchParams();
    if (params.q) query.set("q", params.q);
    if (params.sectionId) query.set("sectionId", params.sectionId);
    if (params.withAllergy) query.set("withAllergy", "true");
    if (params.includeExited) query.set("includeExited", "true");
    const suffix = query.toString() ? `?${query}` : "";
    return api<ChildListItem[]>(`/children${suffix}`, { signal });
  },
  record: (id: string, signal?: AbortSignal) => api<ChildRecord>(`/children/${id}`, { signal }),
  create: (body: { child: ChildIdentity; familyId?: string; family?: { family: FamilyInput; guardian: GuardianInput } }) =>
    api<{ id: string }>("/children", { method: "POST", body }),
  updateIdentity: (id: string, body: ChildIdentity) => api<void>(`/children/${id}`, { method: "PUT", body }),
  updateHealth: (id: string, body: Health) => api<Health>(`/children/${id}/health`, { method: "PUT", body }),
  updateConsents: (id: string, consents: Consent[]) =>
    api<Consent[]>(`/children/${id}/consents`, { method: "PUT", body: { consents } }),
  uploadPhoto: (id: string, file: File) => apiUpload<void>(`/children/${id}/photo`, form({ file }), "PUT"),
  addAuthorizedPerson: (childId: string, body: AuthorizedPersonInput) =>
    api<AuthorizedPerson>(`/children/${childId}/authorized-persons`, { method: "POST", body }),
  updateAuthorizedPerson: (id: string, body: AuthorizedPersonInput) =>
    api<AuthorizedPerson>(`/authorized-persons/${id}`, { method: "PUT", body }),
  deleteAuthorizedPerson: (id: string) => api<void>(`/authorized-persons/${id}`, { method: "DELETE" }),
  uploadAuthorizedPersonPhoto: (id: string, file: File) =>
    apiUpload<void>(`/authorized-persons/${id}/photo`, form({ file }), "PUT"),
  addDocument: (childId: string, input: { file: File; name: string; category: DocumentCategory; validUntil: string | null }) =>
    apiUpload<ChildDocument>(
      `/children/${childId}/documents`,
      form({ file: input.file, name: input.name, category: input.category, validUntil: input.validUntil }),
    ),
  downloadDocument: async (doc: ChildDocument) => {
    const { blob, fileName } = await apiBlob(`/documents/${doc.id}/content`);
    saveBlob(blob, fileName ?? doc.fileName);
  },
  deleteDocument: (id: string) => api<void>(`/documents/${id}`, { method: "DELETE" }),
};

export type AuthorizedPersonInput = {
  firstName: string;
  lastName: string;
  relationship: string;
  phone: string | null;
  idVerified: boolean;
};

export const familiesApi = {
  search: (q: string, signal?: AbortSignal) =>
    api<FamilySummary[]>(`/families${q ? `?q=${encodeURIComponent(q)}` : ""}`, { signal }),
  update: (id: string, body: FamilyInput) => api<void>(`/families/${id}`, { method: "PUT", body }),
  addGuardian: (familyId: string, body: GuardianInput) =>
    api<Guardian>(`/families/${familyId}/guardians`, { method: "POST", body }),
  updateGuardian: (id: string, body: GuardianInput) => api<Guardian>(`/guardians/${id}`, { method: "PUT", body }),
  deleteGuardian: (id: string) => api<void>(`/guardians/${id}`, { method: "DELETE" }),
  export: async (familyId: string) => {
    const { blob, fileName } = await apiBlob(`/families/${familyId}/export`);
    saveBlob(blob, fileName ?? "export-famille.zip");
  },
  purge: (familyId: string, confirmation: string) =>
    api<void>(`/families/${familyId}/purge`, { method: "POST", body: { confirmation } }),
};

function form(fields: Record<string, string | Blob | null>) {
  const data = new FormData();
  for (const [key, value] of Object.entries(fields)) {
    if (value !== null && value !== "") data.append(key, value);
  }
  return data;
}
