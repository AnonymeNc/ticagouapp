import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";

export type OpeningHours = { dayOfWeek: number; opensAt: string; closesAt: string };

export type Structure = {
  id: string;
  name: string;
  city: string | null;
  ridet: string | null;
  agrementNumber: string | null;
  agrementDate: string | null;
  capacity: number;
  invoiceFooter: string | null;
  hasLogo: boolean;
  openingHours: OpeningHours[];
  contact: { address: string | null; postalCode: string | null; phone: string | null; email: string | null };
  bank: { holder: string | null; bankName: string | null; iban: string | null; bic: string | null; printRib: boolean };
  passageRules: { propose: boolean; firstOfMonth: boolean; blockFull: boolean };
  version: number;
};

/** Structure de l'utilisateur connecté. */
export function useStructure() {
  return useQuery({
    queryKey: ["structure"],
    queryFn: ({ signal }) => api<Structure>("/structure", { signal }),
    staleTime: 5 * 60_000,
  });
}
