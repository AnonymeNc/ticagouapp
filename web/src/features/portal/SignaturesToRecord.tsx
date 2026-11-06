import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { FileSignature } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { Button } from "@/components/ui/button";
import { errorMessage } from "@/lib/api";
import { formatDate, formatTime } from "@/lib/datetime";
import { portalApi, portalKeys } from "./api";

/**
 * Signatures reçues du portail, à enregistrer : la version du contrat est alors signée (PDF joint) ou l'autorisation
 * accordée. La preuve (nom saisi, date, adresse IP, empreinte du PDF) est affichée et gardée.
 */
export function SignaturesToRecord() {
  const queryClient = useQueryClient();
  const documents = useQuery({ queryKey: portalKeys.documents(), queryFn: ({ signal }) => portalApi.documents(undefined, signal) });
  const [error, setError] = useState<string | null>(null);
  const list = documents.data ?? [];
  if (list.length === 0 && !error) return null;

  const record = async (id: string) => {
    setError(null);
    try {
      await portalApi.record(id);
      await queryClient.invalidateQueries({ queryKey: ["portal"] });
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <h2 className="m-0 text-title-3">Signatures reçues à enregistrer</h2>
      <ul className="m-0 flex list-none flex-col gap-2 p-0">
        {list.map((d) => (
          <li key={d.id} className="flex flex-wrap items-center gap-4 rounded-md border border-border-subtle px-4 py-3">
            <FileSignature aria-hidden className="size-5 text-lagon-700" />
            <span className="flex grow flex-col">
              <span className="font-semibold">{d.title}{d.childName ? ` — ${d.childName}` : ""}</span>
              <span className="text-small text-muted-foreground">
                Signé par {d.signerName} le {d.signedAt && `${formatDate(new Date(d.signedAt))} à ${formatTime(new Date(d.signedAt))}`}
                {d.signerIp && ` · IP ${d.signerIp}`} · empreinte {d.sha256?.slice(0, 12)}…
              </span>
            </span>
            <Button onClick={() => void record(d.id)}>Enregistrer la signature</Button>
          </li>
        ))}
      </ul>
      {error && <Alert>{error}</Alert>}
    </section>
  );
}
