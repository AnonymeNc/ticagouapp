import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";

export type Section = {
  id: string;
  name: string;
  minAgeMonths: number;
  maxAgeMonths: number | null;
  capacity: number;
  color: string;
  displayOrder: number;
};

export function useSections() {
  return useQuery({
    queryKey: ["sections"],
    queryFn: ({ signal }) => api<Section[]>("/sections", { signal }),
    staleTime: 5 * 60_000,
  });
}

/** Fond clair dérivé de la couleur de section (équivalent des tokens --section-*-soft). */
export function softColor(color: string) {
  return `color-mix(in srgb, ${color} 12%, white)`;
}
