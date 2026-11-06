import { useEffect, useState } from "react";
import { Alert, FocusLayout } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { qrDataUrl } from "@/features/settings/DevicesTab";
import { api, errorMessage } from "@/lib/api";
import { useAuth } from "./AuthProvider";

type MfaSetup = { secret: string; uri: string };

/**
 * Double authentification obligatoire pour la direction et la comptabilité (ADR 0013) : tant qu'elle n'est pas
 * enregistrée, le serveur refuse tout le reste. Pas de maquette : écran bâti sur FocusLayout, comme la connexion.
 */
export function MfaEnrollPage() {
  const { reloadSession, logout } = useAuth();
  const [setup, setSetup] = useState<MfaSetup | null>(null);
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  useEffect(() => {
    api<MfaSetup>("/auth/mfa/start", { method: "POST" }).then(setSetup, (e) => setError(errorMessage(e)));
  }, []);

  const confirm = async () => {
    setPending(true);
    setError(null);
    try {
      await api("/auth/mfa/confirm", { method: "POST", body: { code } });
      await reloadSession();
    } catch (e) {
      setError(errorMessage(e));
      setCode("");
    } finally {
      setPending(false);
    }
  };

  return (
    <FocusLayout>
      <h1 className="m-0 font-display text-title-1">Protégez votre compte</h1>
      <p className="mt-2 mb-6 text-muted-foreground">
        Ti’Cagou est en ligne et contient des données de santé d’enfants : votre compte demande un code en plus du mot de
        passe. Installez une application d’authentification sur votre téléphone (Google Authenticator, Microsoft
        Authenticator…), puis scannez ce code.
      </p>
      {setup && (
        <div className="flex flex-col items-center gap-2">
          <img src={qrDataUrl(setup.uri)} alt="QR code à scanner avec votre application d’authentification" className="size-48 rounded bg-white p-1" />
          <p className="m-0 text-small break-all text-muted-foreground">Ou saisissez cette clé : <span className="font-mono">{setup.secret}</span></p>
        </div>
      )}
      <form
        className="mt-6 flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          void confirm();
        }}
      >
        <FormField label="Code affiché par l’application">
          <Input inputMode="numeric" maxLength={6} autoComplete="one-time-code" value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} />
        </FormField>
        {error && <Alert>{error}</Alert>}
        <Button type="submit" disabled={pending || code.length !== 6} className="h-10">
          {pending ? "Vérification…" : "Activer"}
        </Button>
        <Button type="button" variant="ghost" onClick={() => void logout()}>
          Se déconnecter
        </Button>
      </form>
    </FocusLayout>
  );
}
