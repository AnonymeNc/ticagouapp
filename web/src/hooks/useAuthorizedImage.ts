import { useEffect, useState } from "react";
import { apiBlob } from "@/lib/api";

/**
 * Image protégée (photo d'enfant…) : une balise <img> ne peut pas envoyer le jeton, on télécharge
 * donc le fichier avec authentification et on l'affiche via une URL locale.
 */
export function useAuthorizedImage(path: string | null, version?: unknown): string | null {
  const [url, setUrl] = useState<string | null>(null);

  useEffect(() => {
    if (!path) {
      setUrl(null);
      return;
    }
    const controller = new AbortController();
    let objectUrl: string | null = null;
    apiBlob(path, controller.signal)
      .then(({ blob }) => {
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch(() => setUrl(null));
    return () => {
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [path, version]);

  return url;
}
