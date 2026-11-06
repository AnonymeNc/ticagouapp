import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router";
import { Check, ChevronLeft, Eraser, FileDown } from "lucide-react";
import { errorMessage, fileUrl, portal } from "../api";
import { Badge, Banner, TouchButton, cn } from "../components";
import { meKey, useSession } from "../session";
import { documentsKey } from "./DocumentsPage";

/**
 * Signer une autorisation (maquette « Signer une autorisation ») : lecture du PDF, réponse (une autorisation peut être
 * refusée, un contrat non), tracé au doigt et certification. Signature électronique simple : le portail transmet à la
 * crèche le nom du compte, la date, l'adresse IP et l'empreinte du PDF ; le tracé sert de geste de validation et
 * n'est pas conservé. Écart : la maquette détaille la sortie (date, trajet…) ; ici le détail est dans le PDF.
 */
export function SignaturePage() {
  const { structureId = "", documentId = "" } = useParams();
  const { family, child } = useSession();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const documents = useQuery({ queryKey: documentsKey, queryFn: ({ signal }) => portal.documents(signal) });
  const document = documents.data?.find((d) => d.id === documentId && d.structureId === structureId);
  const [decision, setDecision] = useState<"ACCEPTE" | "REFUSE">("ACCEPTE");
  const [signed, setSigned] = useState(false);
  const [certified, setCertified] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!document) {
    return <div className="p-4">{documents.isPending ? "Chargement…" : <Banner tone="danger">Document introuvable.</Banner>}</div>;
  }
  const isContract = document.data.kind === "CONTRAT";
  const childName = family.children.find((c) => c.id === document.data.childId)?.firstName ?? child?.firstName ?? "l’enfant";

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await portal.sign(structureId, documentId, isContract ? "ACCEPTE" : decision);
      await queryClient.invalidateQueries({ queryKey: documentsKey });
      await queryClient.invalidateQueries({ queryKey: meKey });
      navigate("/documents");
    } catch (e) {
      setError(errorMessage(e));
      setBusy(false);
    }
  };

  return (
    <div className="flex flex-col gap-5 px-4 pt-3 pb-6">
      <Link to="/documents" className="flex min-h-11 items-center gap-1 self-start text-[17px] font-semibold no-underline">
        <ChevronLeft aria-hidden className="size-[22px]" />Documents
      </Link>
      <header className="flex flex-col gap-2">
        <Badge tone="warning">À signer</Badge>
        <h1 className="m-0 font-display text-[24px] leading-[30px] font-semibold">{document.data.title}</h1>
      </header>
      {document.hasFile && (
        <a href={fileUrl(structureId, documentId)} target="_blank" rel="noreferrer"
          className="flex min-h-touch items-center justify-center gap-2 rounded-lg border border-border-control bg-surface text-[17px] font-semibold no-underline">
          <FileDown aria-hidden className="size-5" />Lire le document (PDF)
        </a>
      )}

      {!isContract && (
        <fieldset className="m-0 flex flex-col gap-2.5 border-0 p-0">
          <legend className="mb-2.5 p-0 text-[17px] font-semibold">Votre réponse</legend>
          {(["ACCEPTE", "REFUSE"] as const).map((d) => (
            <button key={d} type="button" aria-pressed={decision === d} onClick={() => setDecision(d)}
              className={cn("flex min-h-touch items-center justify-center gap-2 rounded-full px-4 text-[17px] font-semibold",
                decision === d ? "border-2 border-lagon-600 bg-lagon-100 text-lagon-700" : "border border-border-control bg-surface")}>
              {decision === d && <Check aria-hidden className="size-[18px]" />}
              {d === "ACCEPTE" ? "J’autorise" : "Je n’autorise pas"}
            </button>
          ))}
        </fieldset>
      )}

      <SignaturePad onChange={setSigned} />

      <label className="flex min-h-touch items-center gap-3 text-[17px]">
        <input type="checkbox" checked={certified} onChange={(e) => setCertified(e.target.checked)} className="size-6 accent-lagon-600" />
        Je certifie être responsable légal de {childName}.
      </label>
      {error && <Banner tone="danger">{error}</Banner>}
      <TouchButton disabled={busy || !signed || !certified} onClick={() => void submit()}>
        <Check aria-hidden className="size-5" />
        {isContract ? "Signer le contrat" : decision === "ACCEPTE" ? "Signer l’autorisation" : "Envoyer mon refus"}
      </TouchButton>
      <TouchButton variant="quiet" onClick={() => navigate("/documents")}>Plus tard</TouchButton>
    </div>
  );
}

/** Zone de tracé au doigt ; signale seulement qu'un tracé existe. */
function SignaturePad({ onChange }: { onChange: (signed: boolean) => void }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const drawing = useRef(false);
  const [empty, setEmpty] = useState(true);

  useEffect(() => {
    const c = canvas.current;
    if (!c) return;
    const ratio = window.devicePixelRatio || 1;
    c.width = c.clientWidth * ratio;
    c.height = c.clientHeight * ratio;
    const ctx = c.getContext("2d");
    ctx?.scale(ratio, ratio);
  }, []);

  const point = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    return [e.clientX - rect.left, e.clientY - rect.top] as const;
  };
  const ctx = () => canvas.current?.getContext("2d") ?? null;

  const clear = () => {
    const c = canvas.current;
    ctx()?.clearRect(0, 0, c?.width ?? 0, c?.height ?? 0);
    setEmpty(true);
    onChange(false);
  };

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center justify-between">
        <span id="sig-label" className="text-[17px] font-semibold">Votre signature</span>
        <button type="button" onClick={clear} className="flex min-h-11 items-center gap-1 text-[16px] font-semibold text-lagon-700">
          <Eraser aria-hidden className="size-5" />Effacer
        </button>
      </div>
      <div className="relative">
        <canvas ref={canvas} role="img" aria-labelledby="sig-label" className="block h-40 w-full touch-none rounded-lg border-2 border-dashed border-border-control bg-surface"
          onPointerDown={(e) => {
            drawing.current = true;
            e.currentTarget.setPointerCapture(e.pointerId);
            const c = ctx();
            if (!c) return;
            const [x, y] = point(e);
            c.lineWidth = 2.5;
            c.lineCap = "round";
            c.strokeStyle = "#2b2620";
            c.beginPath();
            c.moveTo(x, y);
          }}
          onPointerMove={(e) => {
            if (!drawing.current) return;
            const c = ctx();
            const [x, y] = point(e);
            c?.lineTo(x, y);
            c?.stroke();
            if (empty) {
              setEmpty(false);
              onChange(true);
            }
          }}
          onPointerUp={() => { drawing.current = false; }}
        />
        {empty && <span className="pointer-events-none absolute inset-0 flex items-center justify-center text-[16px] text-ink-muted">Signez avec le doigt</span>}
      </div>
    </div>
  );
}
