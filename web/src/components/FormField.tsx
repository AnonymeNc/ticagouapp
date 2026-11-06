import { cloneElement, isValidElement, useId, type ReactElement, type ReactNode } from "react";
import { Label } from "@/components/ui/label";
import { cn } from "@/lib/utils";

type Props = {
  label: string;
  hint?: ReactNode;
  error?: string;
  className?: string;
  /** Un seul contrôle (input, select…) : reçoit id, aria-invalid et aria-describedby. */
  children: ReactElement<Record<string, unknown>>;
};

/** Libellé + contrôle + aide + message d'erreur, reliés pour les lecteurs d'écran. */
export function FormField({ label, hint, error, className, children }: Props) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(" ") || undefined;
  const control = isValidElement(children)
    ? cloneElement(children, { id, "aria-invalid": error ? true : undefined, "aria-describedby": describedBy })
    : children;

  return (
    <div className={cn("flex flex-col gap-1.5", className)}>
      <Label htmlFor={id}>{label}</Label>
      {control}
      {hint && (
        <p id={hintId} className="text-small text-muted-foreground">
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} className="text-small font-semibold text-danger-700">
          {error}
        </p>
      )}
    </div>
  );
}
