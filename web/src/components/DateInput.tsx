import { useRef, type ComponentProps, type Ref } from "react";
import { CalendarDays } from "lucide-react";
import { Input } from "@/components/ui/input";
import { isoToDateFr, parseDateFr } from "@/lib/datetime";
import { cn } from "@/lib/utils";

type Props = Omit<ComponentProps<"input">, "type">;

/** Écrit une valeur dans le champ comme une saisie au clavier : register() et les champs contrôlés la reçoivent. */
function typeInto(input: HTMLInputElement, value: string) {
  const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set;
  setValue?.call(input, value);
  input.dispatchEvent(new Event("input", { bubbles: true }));
}

function assignRef<T>(ref: Ref<T> | undefined, value: T | null) {
  if (typeof ref === "function") ref(value);
  else if (ref) (ref as { current: T | null }).current = value;
}

/**
 * Champ date JJ/MM/AAAA : saisie au clavier ou choix dans le calendrier du navigateur (bouton à droite). Mêmes props
 * qu'Input : s'emploie avec register() comme avec value/onChange, et dans un FormField.
 */
export function DateInput({ className, ref, disabled, placeholder = "JJ/MM/AAAA", inputMode = "numeric", ...props }: Props) {
  const text = useRef<HTMLInputElement | null>(null);
  const picker = useRef<HTMLInputElement>(null);

  const open = () => {
    const calendar = picker.current;
    if (!calendar || !text.current) return;
    calendar.value = parseDateFr(text.current.value) ?? "";
    try {
      calendar.showPicker();
    } catch {
      // Navigateur sans showPicker : le champ date natif prend le focus et s'ouvre au clic.
      calendar.focus();
      calendar.click();
    }
  };

  return (
    <div className={cn("relative w-full", className)}>
      <Input
        {...props}
        ref={(el) => {
          text.current = el;
          assignRef(ref, el);
        }}
        disabled={disabled}
        placeholder={placeholder}
        inputMode={inputMode}
        autoComplete="off"
        className="pr-9"
      />
      <button
        type="button"
        onClick={open}
        disabled={disabled}
        aria-label="Choisir dans le calendrier"
        title="Choisir dans le calendrier"
        className="absolute inset-y-0 right-0 flex w-9 items-center justify-center rounded-r-md text-muted-foreground outline-none hover:text-foreground focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-50"
      >
        <CalendarDays aria-hidden className="size-4" />
      </button>
      {/* Calendrier natif, invisible : il s'ouvre sous le champ et renvoie la date choisie au format JJ/MM/AAAA. */}
      <input
        ref={picker}
        type="date"
        tabIndex={-1}
        aria-hidden
        className="pointer-events-none absolute right-0 bottom-0 size-px opacity-0"
        onChange={(e) => {
          if (!e.target.value || !text.current) return;
          typeInto(text.current, isoToDateFr(e.target.value));
          text.current.focus();
        }}
      />
    </div>
  );
}
