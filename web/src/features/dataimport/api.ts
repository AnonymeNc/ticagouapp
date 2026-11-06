/** Types et appels de la reprise des données (miroir des DTO serveur, en attendant le client généré). */
import { apiBlob, apiUpload, saveBlob } from "@/lib/api";
import type { Severity } from "@/features/children/api";

export type ImportIssue = { sheet: string; row: number | null; message: string };

export type ImportCounts = {
  families: number;
  guardians: number;
  children: number;
  allergies: number;
  pais: number;
  authorizedPersons: number;
};

export type ChildReview = {
  reference: string;
  firstName: string;
  lastName: string;
  birthDate: string;
  section: string | null;
  allergies: { allergen: string; reaction: string | null; severity: Severity }[];
  pai: boolean;
  paiReason: string | null;
  diet: string | null;
  pickupPersons: string[];
};

export type ImportPreview = {
  fileHash: string;
  counts: ImportCounts;
  errors: ImportIssue[];
  warnings: ImportIssue[];
  children: ChildReview[];
};

const withFile = (file: File) => {
  const form = new FormData();
  form.append("file", file);
  return form;
};

export const dataImportApi = {
  downloadTemplate: async () => {
    const { blob, fileName } = await apiBlob("/data-import/template");
    saveBlob(blob, fileName ?? "reprise-ticagou.xlsx");
  },
  preview: (file: File) => apiUpload<ImportPreview>("/data-import/preview", withFile(file)),
  commit: (file: File, fileHash: string) => {
    const form = withFile(file);
    form.append("fileHash", fileHash);
    form.append("healthReviewed", "true");
    return apiUpload<ImportCounts>("/data-import/commit", form);
  },
};
