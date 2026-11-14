import { useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useNavigate, useSearchParams } from "react-router";
import { errorMessage, portal } from "../api";
import { Banner, Logo, TextField, TouchButton } from "../components";
import { IS_APP } from "../native";
import { meKey } from "../session";

/**
 * Connexion (maquette « Connexion »). Écart validé : pas de mot de passe. Le parent saisit son e-mail et reçoit un
 * lien valable 15 minutes ; s'il l'ouvre sur un autre appareil, il saisit le code à 6 chiffres du même e-mail.
 * Le lien ouvre /connexion#jeton=… : le jeton reste dans le fragment, jamais envoyé dans l'adresse au serveur.
 */
export function LoginPage() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [step, setStep] = useState<"email" | "code">("email");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const tried = useRef(false);
  const [params] = useSearchParams();
  const deleted = params.get("compte") === "supprime";

  const done = async () => {
    await queryClient.invalidateQueries({ queryKey: meKey });
    navigate("/", { replace: true });
  };

  useEffect(() => {
    const token = new URLSearchParams(window.location.hash.slice(1)).get("jeton");
    if (!token || tried.current) return;
    tried.current = true;
    window.history.replaceState(null, "", window.location.pathname);
    setBusy(true);
    portal.verifyToken(token).then(done).catch((e) => {
      setError(errorMessage(e));
      setBusy(false);
    });
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const request = async () => {
    setError(null);
    setBusy(true);
    try {
      await portal.requestLink(email.trim());
      setStep("code");
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const verify = async () => {
    setError(null);
    setBusy(true);
    try {
      await portal.verifyCode(email.trim(), code);
      await done();
    } catch (e) {
      setError(errorMessage(e));
      setBusy(false);
    }
  };

  return (
    <main className="mx-auto flex min-h-dvh max-w-[480px] flex-col px-6 pt-14 pb-8">
      <div className="flex flex-col gap-5">
        <Logo size={48} />
        <div className="flex flex-col gap-2">
          <h1 className="m-0 font-display text-[32px] leading-[38px] font-semibold">Bonjour</h1>
          <p className="m-0 text-[17px] text-ink-muted">Suivez la journée de votre enfant à la crèche, où que vous soyez.</p>
        </div>
      </div>
      <div className="grow" />
      {step === "email" ? (
        <form className="flex flex-col gap-4" onSubmit={(e) => { e.preventDefault(); void request(); }}>
          <TextField label="Adresse e-mail" type="email" name="email" autoComplete="email" required value={email}
            onChange={(e) => setEmail(e.target.value)} hint="Celle que vous avez donnée à la crèche." />
          {deleted && <Banner tone="success">Votre compte est supprimé. La crèche est prévenue et vous recontactera pour le dossier de la famille.</Banner>}
          {error && <Banner tone="danger">{error}</Banner>}
          <TouchButton type="submit" disabled={busy || !email.trim()}>{IS_APP ? "Recevoir mon code de connexion" : "Recevoir mon lien de connexion"}</TouchButton>
        </form>
      ) : (
        <form className="flex flex-col gap-4" onSubmit={(e) => { e.preventDefault(); void verify(); }}>
          <Banner>
            Si cette adresse est connue de la crèche, un e-mail vient de partir vers <strong>{email.trim()}</strong>.
            {IS_APP ? " Saisissez le code à 6 chiffres qu’il contient." : " Touchez le lien qu’il contient, ou saisissez son code ci-dessous."}
          </Banner>
          <TextField label="Code à 6 chiffres" name="code" inputMode="numeric" autoComplete="one-time-code" maxLength={7}
            value={code} onChange={(e) => setCode(e.target.value.replace(/[^0-9 ]/g, ""))} />
          {error && <Banner tone="danger">{error}</Banner>}
          <TouchButton type="submit" disabled={busy || code.replace(/\s/g, "").length !== 6}>Se connecter</TouchButton>
          <TouchButton variant="quiet" onClick={() => { setStep("email"); setCode(""); setError(null); }}>Changer d’adresse ou renvoyer l’e-mail</TouchButton>
        </form>
      )}
      <p className="mt-5 mb-0 text-center text-[15px] text-ink-muted">
        Le lien et le code sont valables 15 minutes. Vous restez connecté 30 jours sur ce téléphone.
      </p>
    </main>
  );
}
