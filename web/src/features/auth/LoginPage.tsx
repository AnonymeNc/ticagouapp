import { useState } from "react";
import { Navigate, useLocation, useNavigate } from "react-router";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Alert, FocusLayout } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ApiError, errorMessage } from "@/lib/api";
import { useAuth } from "./AuthProvider";
import { Splash } from "./DesktopGate";
import { useSetupStatus } from "./useSetupStatus";

const schema = z.object({
  login: z.string().trim().min(1, "Indiquez votre identifiant."),
  password: z.string().min(1, "Indiquez votre mot de passe."),
});

type Values = z.infer<typeof schema>;

export function LoginPage() {
  const { state, login } = useAuth();
  const status = useSetupStatus();
  const navigate = useNavigate();
  const location = useLocation();
  const [error, setError] = useState<string | null>(null);
  // Mot de passe accepté, le serveur demande le code de l'application d'authentification.
  const [askCode, setAskCode] = useState(false);
  const [code, setCode] = useState("");
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { login: "", password: "" } });

  if (status.isPending || state.status === "loading") return <Splash />;
  if (status.data && !status.data.initialized) return <Navigate to="/setup" replace />;
  if (state.status === "authenticated") {
    return <Navigate to={(location.state as { from?: string } | null)?.from ?? "/"} replace />;
  }

  const onSubmit = form.handleSubmit(async (values) => {
    setError(null);
    try {
      await login(values.login, values.password, askCode ? code : undefined);
      navigate((location.state as { from?: string } | null)?.from ?? "/", { replace: true });
    } catch (e) {
      if (e instanceof ApiError && e.code === "mfa_required") {
        setAskCode(true);
        return;
      }
      setError(errorMessage(e));
      if (e instanceof ApiError && e.code === "mfa_invalid") {
        setCode("");
        return;
      }
      form.resetField("password");
    }
  });

  return (
    <FocusLayout>
      <h1 className="m-0 font-display text-title-1">Connexion</h1>
      {status.data?.structureName && <p className="mt-1 text-muted-foreground">{status.data.structureName}</p>}
      <form onSubmit={onSubmit} className="mt-6 flex flex-col gap-4" noValidate>
        {askCode ? (
          <FormField label="Code de votre application d’authentification">
            <Input inputMode="numeric" maxLength={6} autoComplete="one-time-code" autoFocus value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} />
          </FormField>
        ) : (
          <>
            <FormField label="Identifiant" error={form.formState.errors.login?.message}>
              <Input autoComplete="username" autoFocus {...form.register("login")} />
            </FormField>
            <FormField label="Mot de passe" error={form.formState.errors.password?.message}>
              <Input type="password" autoComplete="current-password" {...form.register("password")} />
            </FormField>
          </>
        )}
        {error && <Alert>{error}</Alert>}
        <Button type="submit" disabled={form.formState.isSubmitting} className="mt-2 h-10">
          {form.formState.isSubmitting ? "Connexion…" : "Se connecter"}
        </Button>
        <p className="m-0 text-small text-muted-foreground">
          {askCode ? "Téléphone perdu ? Demandez à la direction de réinitialiser votre accès, ou contactez Ti’Cagou." : "Mot de passe oublié ? Demandez à la direction de vous en définir un nouveau ; la direction, elle, demande un lien à l’éditeur de Ti’Cagou."}
        </p>
      </form>
    </FocusLayout>
  );
}
