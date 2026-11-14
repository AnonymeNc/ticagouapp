import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Bell, ShieldCheck } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { errorMessage } from "@/lib/api";
import { isNativeApp } from "@/lib/native/platform";
import { categoryLabels, pushSettings, savePushCategories, type Category } from "@/lib/native/push";
import { cn } from "@/lib/utils";

const ORDER: Category[] = ["DEPART", "ENCADREMENT", "SAUVEGARDE", "TABLETTE"];

/** Réglage des alertes reçues sur ce téléphone (maquette C7, ADR 0026). */
export function NotificationsPage() {
  const queryClient = useQueryClient();
  const native = isNativeApp();
  const settings = useQuery({ queryKey: ["push-settings"], queryFn: pushSettings, enabled: native });
  const save = useMutation({
    mutationFn: savePushCategories,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["push-settings"] }),
  });
  const chosen = new Set(settings.data?.categories ?? []);

  return (
    <>
      <div>
        <h1 className="m-0 font-display text-display">Notifications</h1>
        <p className="mt-1 mb-0 text-muted-foreground">Sur ce téléphone, pour cette crèche.</p>
      </div>
      {!native && (
        <p className="m-0 flex items-start gap-2 rounded-md bg-info-100 px-3 py-2.5 text-info-700">
          <Bell aria-hidden className="mt-0.5 size-4 shrink-0" />
          Les alertes arrivent sur le téléphone de la direction, dans l’application Ti’Cagou Direction ; elles se règlent
          depuis l’application.
        </p>
      )}
      {native && settings.isSuccess && settings.data === null && (
        <p className="m-0 rounded-md bg-warning-100 px-3 py-2.5 text-warning-700">
          Notifications désactivées dans les réglages du téléphone : autorisez-les pour Ti’Cagou, puis rouvrez l’application.
        </p>
      )}
      {native && settings.data && (
        <ul className="m-0 flex list-none flex-col divide-y divide-border-subtle overflow-hidden rounded-lg border border-border-subtle bg-card p-0">
          {ORDER.map((category) => {
            const on = chosen.has(category);
            return (
              <li key={category} className="flex min-h-16 items-center gap-3 px-4 py-2">
                <span className="min-w-0 flex-1">
                  <span className="block font-semibold">{categoryLabels[category].title}</span>
                  <span className="block text-small text-muted-foreground">{categoryLabels[category].hint}</span>
                </span>
                <button
                  type="button"
                  role="switch"
                  aria-checked={on}
                  aria-label={categoryLabels[category].title}
                  disabled={save.isPending}
                  onClick={() => {
                    const next = new Set(chosen);
                    if (on) next.delete(category);
                    else next.add(category);
                    save.mutate(ORDER.filter((c) => next.has(c)));
                  }}
                  className={cn("relative h-7 w-12 shrink-0 rounded-full transition-colors", on ? "bg-lagon-600" : "bg-border")}
                >
                  <span className={cn("absolute top-0.5 size-6 rounded-full bg-white shadow-1 transition-all", on ? "left-[22px]" : "left-0.5")} />
                </button>
              </li>
            );
          })}
        </ul>
      )}
      {save.isError && <Alert>{errorMessage(save.error)}</Alert>}
      <p className="m-0 flex items-start gap-2 text-small text-muted-foreground">
        <ShieldCheck aria-hidden className="mt-0.5 size-4 shrink-0" />
        Les notifications passent par Apple ou Google : elles ne contiennent jamais d’information de santé ni de message,
        seulement le prénom et l’initiale d’un enfant quand c’est nécessaire.
      </p>
    </>
  );
}
