import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import qrcode from "qrcode-generator";
import { Plus, TabletSmartphone } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { TonePill, type Tone } from "@/features/enrollment/components";
import { errorMessage } from "@/lib/api";
import { formatDate, formatTime } from "@/lib/datetime";
import { devicesApi, type Device, type DeviceState, type PairingCode } from "./api";

const states: Record<DeviceState, { tone: Tone; label: string }> = {
  EN_ATTENTE: { tone: "info", label: "En attente d’appairage" },
  APPAIREE: { tone: "success", label: "Appairée" },
  RETIREE: { tone: "neutral", label: "Retirée" },
};

const stamp = (iso: string | null) => (iso ? `${formatDate(new Date(iso))} · ${formatTime(new Date(iso))}` : "—");

/** Paramètres › Tablettes : appairage des tablettes d’accueil par QR code ou par code (ADR 0005, ADR 0008). */
export function DevicesTab() {
  const queryClient = useQueryClient();
  const devices = useQuery({ queryKey: ["devices"], queryFn: ({ signal }) => devicesApi.list(signal) });
  const [naming, setNaming] = useState(false);
  const [code, setCode] = useState<PairingCode | null>(null);
  const [error, setError] = useState<string | null>(null);
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["devices"] });

  const renew = async (device: Device) => {
    try {
      setCode(await devicesApi.renewCode(device.id));
      await refresh();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const revoke = async (device: Device) => {
    if (!window.confirm(`Retirer « ${device.name} » ? La tablette devra être appairée à nouveau pour pointer.`)) return;
    try {
      await devicesApi.revoke(device.id);
      await refresh();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const active = devices.data?.filter((d) => d.state === "APPAIREE").length ?? 0;

  return (
    <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="m-0 text-title-3">Tablettes</h2>
          <p className="m-0 text-muted-foreground">
            Les tablettes se synchronisent par internet, et continuent de pointer si la connexion coupe. {devices.data && <>{active} tablette{active > 1 ? "s appairées" : " appairée"}.</>}
          </p>
        </div>
        <Button onClick={() => setNaming(true)}>
          <Plus aria-hidden />
          Appairer une tablette
        </Button>
      </div>
      {error && <Alert>{error}</Alert>}
      <table className="w-full border-collapse text-left">
        <caption className="sr-only">Tablettes appairées</caption>
        <thead>
          <tr className="border-b border-border-subtle text-label text-muted-foreground">
            <th scope="col" className="py-2">Nom</th>
            <th scope="col" className="py-2">Appareil</th>
            <th scope="col" className="py-2">Dernière synchronisation</th>
            <th scope="col" className="py-2">État</th>
            <th scope="col" className="py-2"><span className="sr-only">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          {devices.data?.map((d) => (
            <tr key={d.id} className="border-b border-border-subtle last:border-0">
              <td className="py-2">{d.name}</td>
              <td className="max-w-64 truncate py-2 text-small text-muted-foreground" title={d.userAgent ?? undefined}>{d.userAgent ?? "—"}</td>
              <td className="py-2 tabular-nums">{stamp(d.lastSeenAt)}</td>
              <td className="py-2"><TonePill tone={states[d.state].tone}>{states[d.state].label}</TonePill></td>
              <td className="py-2 text-right whitespace-nowrap">
                {d.state !== "RETIREE" && (
                  <>
                    <Button variant="ghost" size="sm" onClick={() => void renew(d)}>Nouveau code</Button>
                    <Button variant="ghost" size="sm" className="text-danger-700" onClick={() => void revoke(d)}>Retirer</Button>
                  </>
                )}
              </td>
            </tr>
          ))}
          {devices.data?.length === 0 && (
            <tr>
              <td colSpan={5} className="py-4 text-muted-foreground">Aucune tablette : appairez la tablette d’accueil pour pointer.</td>
            </tr>
          )}
        </tbody>
      </table>
      {naming && (
        <NameDialog
          onClose={() => setNaming(false)}
          onCreated={async (created) => {
            setNaming(false);
            setCode(created);
            await refresh();
          }}
        />
      )}
      {code && <CodeDialog code={code} onClose={() => setCode(null)} />}
    </section>
  );
}

function NameDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (code: PairingCode) => Promise<void> }) {
  const [name, setName] = useState("Accueil — entrée");
  const [error, setError] = useState<string | null>(null);
  const submit = async () => {
    if (!name.trim()) return setError("Donnez un nom à la tablette (« Salle des Bébés »).");
    try {
      await onCreated(await devicesApi.create(name.trim()));
    } catch (e) {
      setError(errorMessage(e));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Appairer une tablette</DialogTitle>
          <DialogDescription>Le nom permet de la reconnaître dans la liste et le journal.</DialogDescription>
        </DialogHeader>
        <FormField label="Nom de la tablette"><Input value={name} maxLength={60} onChange={(e) => setName(e.target.value)} /></FormField>
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button onClick={() => void submit()}>Obtenir le code</Button>
          <Button variant="ghost" onClick={onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** Image du QR code d'une URL (ADR 0008 : qrcode-generator, sans appel réseau). */
export function qrDataUrl(text: string): string {
  const qr = qrcode(0, "M");
  qr.addData(text);
  qr.make();
  return qr.createDataURL(6, 2);
}

/** Lien du QR code : la tablette ouvre le pointage et s'appaire seule, sans certificat à installer. */
export function pairingLink(code: string): string {
  return `${window.location.origin}/kiosk?pair=${code}`;
}

function CodeDialog({ code, onClose }: { code: PairingCode; onClose: () => void }) {
  const link = pairingLink(code.code);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Code d’appairage de « {code.device.name} »</DialogTitle>
          <DialogDescription>
            Scannez le QR code avec l’appareil photo de la tablette : elle ouvre le pointage et s’appaire seule. Sinon, ouvrez
            l’adresse du serveur suivie de /kiosk et tapez le code.
          </DialogDescription>
        </DialogHeader>
        <div className="flex flex-wrap items-center gap-5 rounded-lg bg-lagon-50 p-5">
          <img src={qrDataUrl(link)} alt={`QR code d’appairage : ${link}`} className="size-40 rounded bg-white p-1" />
          <div className="flex flex-col gap-2">
            <span className="flex items-center gap-2 text-label"><TabletSmartphone aria-hidden className="size-5 text-lagon-600" />Code</span>
            <p className="m-0 font-display text-[44px] leading-none tracking-[0.2em] tabular-nums" aria-label={`Code ${code.code.split("").join(" ")}`}>
              {code.code}
            </p>
          </div>
        </div>
        <p className="m-0 text-small break-all text-muted-foreground">{link}</p>
        <p className="m-0 text-muted-foreground">
          Valable jusqu’à {formatTime(new Date(code.expiresAt))}, une seule fois. Il n’est plus affiché ensuite.
        </p>
        <Button onClick={onClose} className="self-start">Terminé</Button>
      </DialogContent>
    </Dialog>
  );
}
