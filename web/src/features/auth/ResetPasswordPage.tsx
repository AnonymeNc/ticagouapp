import { useState } from "react";
import { Link, useSearchParams } from "react-router";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Alert, FocusLayout } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { api, errorMessage } from "@/lib/api";

const schema = z
  .object({
    password: z.string().min(10, "Au moins 10 caractères.").max(128, "128 caractères au plus."),
    confirm: z.string(),
  })
  .refine((v) => v.password === v.confirm, { path: ["confirm"], message: "Les deux mots de passe sont différents." });

type Values = z.infer<typeof schema>;

/**
 * Lien de réinitialisation envoyé par l'éditeur quand la direction a oublié son mot de passe (console, ordre
 * MOT_DE_PASSE). Usage unique, 24 heures ; la personne choisit elle-même son nouveau mot de passe.
 */
export function ResetPasswordPage() {
  const [params] = useSearchParams();
  const token = params.get("jeton") ?? "";
  const [done, setDone] = useState(false);
  const account = useQuery({
    queryKey: ["password-reset", token],
    queryFn: () => api<{ login: string; name: string }>("/auth/password-reset/check", { method: "POST", body: { token }, auth: false }),
    retry: false,
  });
  const save = useMutation({
    mutationFn: (password: string) => api<void>("/auth/password-reset", { method: "POST", body: { token, password }, auth: false }),
    onSuccess: () => setDone(true),
  });
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { password: "", confirm: "" } });

  return (
    <FocusLayout>
      <h1 className="m-0 font-display text-title-1">Nouveau mot de passe</h1>
      {done ? (
        <>
          <p className="mt-2">Votre mot de passe est changé. Connectez-vous avec lui ; le code de votre application vous sera demandé comme d’habitude.</p>
          <Button asChild className="mt-4 h-10">
            <Link to="/login">Se connecter</Link>
          </Button>
        </>
      ) : account.isPending ? (
        <p className="mt-2 text-muted-foreground">Vérification du lien…</p>
      ) : account.isError ? (
        <div className="mt-4">
          <Alert>{errorMessage(account.error)}</Alert>
        </div>
      ) : (
        <form
          className="mt-6 flex flex-col gap-4"
          noValidate
          onSubmit={form.handleSubmit((v) => save.mutate(v.password))}
        >
          <p className="m-0 text-muted-foreground">
            Compte <strong>{account.data.login}</strong> ({account.data.name}). Ce lien ne sert qu’une fois.
          </p>
          <FormField label="Nouveau mot de passe" error={form.formState.errors.password?.message}>
            <Input type="password" autoComplete="new-password" autoFocus {...form.register("password")} />
          </FormField>
          <FormField label="Retapez-le" error={form.formState.errors.confirm?.message}>
            <Input type="password" autoComplete="new-password" {...form.register("confirm")} />
          </FormField>
          {save.isError && <Alert>{errorMessage(save.error)}</Alert>}
          <Button type="submit" disabled={save.isPending} className="mt-2 h-10">
            {save.isPending ? "Enregistrement…" : "Enregistrer"}
          </Button>
        </form>
      )}
    </FocusLayout>
  );
}
