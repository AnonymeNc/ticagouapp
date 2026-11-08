import { useState, type FormEvent } from "react";
import { useNavigate, useSearchParams } from "react-router";
import { Alert, FocusLayout } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { errorMessage } from "@/lib/api";
import { authApi, useAuth } from "./AuthProvider";

/** Premier compte de la console, par le lien `/premier-compte?jeton=…` (jeton CONSOLE_SETUP_TOKEN de socle.env). */
export function SetupPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const { reload } = useAuth();
  const [login, setLogin] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const token = params.get("jeton") ?? "";

  const mismatch = confirm.length > 0 && confirm !== password;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setPending(true);
    setError(null);
    try {
      await authApi.setup({ token, login, displayName, password });
      await reload();
      navigate("/connexion?cree=1", { replace: true });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setPending(false);
    }
  };

  return (
    <FocusLayout>
      <form className="flex flex-col gap-4" onSubmit={(e) => void submit(e)}>
        <h1 className="m-0 font-display text-title-1">Premier compte</h1>
        <p className="m-0 text-muted-foreground">
          Ce lien ne sert qu’une fois : dès qu’un compte existe, il ne fonctionne plus. La double authentification
          s’enregistre à la première connexion.
        </p>
        {!token && <Alert>Le lien ne contient pas de jeton : utilisez celui de socle.env (CONSOLE_SETUP_TOKEN).</Alert>}
        <FormField label="Identifiant" hint="Minuscules, chiffres, point ou tiret.">
          <Input autoComplete="username" value={login} onChange={(e) => setLogin(e.target.value)} />
        </FormField>
        <FormField label="Nom affiché">
          <Input autoComplete="name" value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
        </FormField>
        <FormField label="Mot de passe" hint="12 caractères au moins.">
          <Input type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </FormField>
        <FormField label="Mot de passe, encore" error={mismatch ? "Les deux mots de passe diffèrent." : undefined}>
          <Input type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
        </FormField>
        {error && <Alert>{error}</Alert>}
        <Button type="submit" className="h-10" disabled={pending || !token || !login || password.length < 12 || confirm !== password}>
          {pending ? "Création…" : "Créer le compte"}
        </Button>
      </form>
    </FocusLayout>
  );
}
