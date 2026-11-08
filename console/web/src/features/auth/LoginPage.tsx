import { useState, type FormEvent } from "react";
import { Alert, FocusLayout } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { errorMessage } from "@/lib/api";
import { qrDataUrl } from "@/lib/qr";
import { authApi, useAuth, type LoginStep } from "./AuthProvider";

/**
 * Connexion à la console (maquette « Connexion ») : identifiant et mot de passe, puis code TOTP. À la première
 * connexion, le QR code à scanner s'affiche avant le code.
 */
export function LoginPage({ notice }: { notice?: string }) {
  const { state, reload, logout } = useAuth();
  const [step, setStep] = useState<LoginStep>(state.login);
  const [login, setLogin] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  const run = async (action: () => Promise<void>) => {
    setPending(true);
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setPending(false);
    }
  };

  const submitPassword = (e: FormEvent) => {
    e.preventDefault();
    void run(async () => {
      const next = await authApi.login(login, password);
      setPassword("");
      setStep(next);
    });
  };

  const submitCode = (e: FormEvent) => {
    e.preventDefault();
    void run(async () => {
      try {
        await authApi.totp(code);
        await reload();
      } catch (err) {
        setCode("");
        throw err;
      }
    });
  };

  const restart = () => {
    void logout();
    setStep({ step: "MOT_DE_PASSE", secret: null, uri: null });
    setCode("");
    setError(null);
  };

  const askingCode = step.step === "TOTP" || step.step === "TOTP_ENROLL";

  return (
    <FocusLayout footer="Réservée à l’éditeur. Accès depuis le réseau autorisé seulement.">
      {!askingCode ? (
        <form className="flex flex-col gap-4" onSubmit={submitPassword}>
          <h1 className="m-0 font-display text-title-1">Connexion</h1>
          {notice && <p className="m-0 rounded-md bg-success-100 px-4 py-3 text-success-700">{notice}</p>}
          {state.setupRequired && (
            <p className="m-0 rounded-md bg-info-100 px-4 py-3 text-info-700">
              Aucun compte n’existe encore. Ouvrez le lien de création : <span className="font-mono">/premier-compte?jeton=…</span>,
              avec le jeton <span className="font-mono">CONSOLE_SETUP_TOKEN</span> de socle.env.
            </p>
          )}
          <FormField label="Identifiant">
            <Input autoComplete="username" autoFocus value={login} onChange={(e) => setLogin(e.target.value)} />
          </FormField>
          <FormField label="Mot de passe">
            <Input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
          </FormField>
          {error && <Alert>{error}</Alert>}
          <Button type="submit" className="h-10" disabled={pending || !login || !password}>
            {pending ? "Vérification…" : "Continuer"}
          </Button>
        </form>
      ) : (
        <form className="flex flex-col gap-4" onSubmit={submitCode}>
          {step.step === "TOTP_ENROLL" ? (
            <>
              <h1 className="m-0 font-display text-title-1">Protégez votre compte</h1>
              <p className="m-0 text-muted-foreground">
                La console donne accès aux factures et, bientôt, aux crèches : un code est demandé à chaque connexion.
                Scannez ce QR code avec votre application d’authentification, puis saisissez le code affiché.
              </p>
              {step.uri && (
                <div className="flex flex-col items-center gap-2">
                  <img src={qrDataUrl(step.uri)} alt="QR code à scanner avec votre application d’authentification" className="size-48 rounded bg-white p-1" />
                  <p className="m-0 text-small break-all text-muted-foreground">
                    Ou saisissez cette clé : <span className="font-mono">{step.secret}</span>
                  </p>
                </div>
              )}
            </>
          ) : (
            <>
              <h1 className="m-0 font-display text-title-1">Code de l’application</h1>
              <p className="m-0 text-muted-foreground">Saisissez le code à 6 chiffres de votre application d’authentification.</p>
            </>
          )}
          <FormField label="Code à 6 chiffres">
            <Input
              inputMode="numeric"
              autoComplete="one-time-code"
              autoFocus
              maxLength={6}
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
              className="h-12 text-center font-display text-title-1 tracking-[0.4em]"
            />
          </FormField>
          {error && <Alert>{error}</Alert>}
          <Button type="submit" className="h-10" disabled={pending || code.length !== 6}>
            {pending ? "Vérification…" : step.step === "TOTP_ENROLL" ? "Activer et se connecter" : "Se connecter"}
          </Button>
          <Button type="button" variant="ghost" onClick={restart}>
            Recommencer
          </Button>
        </form>
      )}
    </FocusLayout>
  );
}
