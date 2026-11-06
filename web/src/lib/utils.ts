import { clsx, type ClassValue } from "clsx";
import { extendTailwindMerge } from "tailwind-merge";

/**
 * Tailles de texte du design system (`--text-*` de index.css). Sans elles, tailwind-merge prend `text-numeric-lg`
 * pour une couleur et le supprime face à `text-danger-700`.
 */
const twMerge = extendTailwindMerge({
  extend: {
    theme: {
      text: ["display", "title-1", "title-2", "title-3", "body", "label", "small", "caption", "numeric-lg",
        "touch-title", "touch-body", "touch-label"],
    },
  },
});

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
