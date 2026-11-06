import { useState, type ReactNode } from "react";
import { NavLink } from "react-router";
import { CalendarDays, Check, FileText, House, MessageSquare, Receipt } from "lucide-react";
import { clsx } from "clsx";
import { childPhotoUrl, type Child } from "./api";

export const cn = clsx;

/** Monogramme provisoire Ti'Cagou (même logo que l'application). */
export function Logo({ size = 32 }: { size?: number }) {
  return (
    <span aria-label="Ti’Cagou" role="img" style={{ width: size, height: size, fontSize: size * 0.42 }}
      className="inline-flex shrink-0 items-center justify-center rounded-[28%] bg-lagon-600 font-display font-semibold text-white">
      TC
    </span>
  );
}

const sectionTone = (section: string | null) => {
  const s = (section ?? "").toLowerCase();
  if (s.startsWith("bé") || s.startsWith("be")) return "bg-section-bebes-soft text-section-bebes ring-section-bebes";
  if (s.startsWith("gr")) return "bg-section-grands-soft text-section-grands ring-section-grands";
  return "bg-section-moyens-soft text-section-moyens ring-section-moyens";
};

export function ChildAvatar({ child, structureId, size = 56 }: { child: Child; structureId: string; size?: number }) {
  const [failed, setFailed] = useState(false);
  const initials = `${child.firstName.charAt(0)}${child.lastInitial ?? ""}`.toUpperCase();
  return child.hasPhoto && !failed ? (
    <img src={childPhotoUrl(structureId, child.id)} alt="" width={size} height={size} onError={() => setFailed(true)}
      className={cn("shrink-0 rounded-full object-cover ring-2 ring-offset-2", sectionTone(child.section))} style={{ width: size, height: size }} />
  ) : (
    <span aria-hidden style={{ width: size, height: size, fontSize: size * 0.36 }}
      className={cn("inline-flex shrink-0 items-center justify-center rounded-full font-bold ring-2 ring-offset-2", sectionTone(child.section))}>
      {initials}
    </span>
  );
}

export function SectionBadge({ section }: { section: string | null }) {
  if (!section) return null;
  return <span className={cn("inline-flex h-6 items-center rounded-sm px-2 text-[13px] font-semibold", sectionTone(section))}>{section}</span>;
}

type Tone = "success" | "warning" | "info" | "neutral" | "danger";
const tones: Record<Tone, string> = {
  success: "bg-success-100 text-success-700",
  warning: "bg-warning-100 text-warning-700",
  info: "bg-info-100 text-info-700",
  neutral: "bg-sunken text-ink-muted",
  danger: "bg-danger-100 text-danger-700",
};

export function Badge({ tone, children, check }: { tone: Tone; children: ReactNode; check?: boolean }) {
  return (
    <span className={cn("inline-flex min-h-7 items-center gap-1 rounded-full px-3 text-[14px] font-semibold", tones[tone])}>
      {check && <Check aria-hidden className="size-4" />}
      {children}
    </span>
  );
}

export function Banner({ tone = "info", children }: { tone?: Tone; children: ReactNode }) {
  return <div role={tone === "danger" ? "alert" : "status"} className={cn("rounded-lg px-4 py-3 text-[16px]", tones[tone])}>{children}</div>;
}

export function TouchButton({ variant = "primary", className, ...props }: React.ComponentProps<"button"> & { variant?: "primary" | "quiet" | "secondary" }) {
  return (
    <button type="button" {...props} className={cn(
      "flex min-h-touch-lg w-full items-center justify-center gap-2 rounded-lg px-5 text-[18px] font-semibold disabled:opacity-50",
      variant === "primary" && "bg-lagon-600 text-white active:bg-lagon-700",
      variant === "secondary" && "border border-border-control bg-surface text-ink",
      variant === "quiet" && "text-lagon-700",
      className,
    )} />
  );
}

export function TextField({ label, hint, ...props }: React.ComponentProps<"input"> & { label: string; hint?: string }) {
  const id = props.id ?? props.name ?? label;
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-[16px] font-semibold">{label}</label>
      <input id={id} {...props} className="h-touch rounded-md border border-border-control bg-surface px-3 text-[17px]" />
      {hint && <span className="text-[15px] text-ink-muted">{hint}</span>}
    </div>
  );
}

/** Barre de navigation du bas, comme la maquette : cinq rubriques, pastilles non lus / à signer. */
export function BottomNav({ unread, toSign }: { unread: number; toSign: number }) {
  const items = [
    { to: "/", label: "Journée", icon: House, count: 0, sr: "" },
    { to: "/messages", label: "Messages", icon: MessageSquare, count: unread, sr: `, ${unread} non lu${unread > 1 ? "s" : ""}` },
    { to: "/absence", label: "Absence", icon: CalendarDays, count: 0, sr: "" },
    { to: "/factures", label: "Factures", icon: Receipt, count: 0, sr: "" },
    { to: "/documents", label: "Documents", icon: FileText, count: toSign, sr: `, ${toSign} à signer` },
  ];
  return (
    <nav aria-label="Navigation principale" className="sticky bottom-0 grid grid-cols-5 border-t border-border-subtle bg-surface px-1 pt-1.5 pb-[max(12px,env(safe-area-inset-bottom))]">
      {items.map(({ to, label, icon: Icon, count, sr }) => (
        <NavLink key={to} to={to} end={to === "/"} className={({ isActive }) => cn(
          "flex min-h-[60px] flex-col items-center justify-center gap-0.5 text-[13px] font-semibold no-underline",
          isActive ? "text-lagon-700" : "text-ink-muted",
        )}>
          {({ isActive }) => (
            <>
              <span className={cn("relative flex h-8 w-14 items-center justify-center rounded-full", isActive && "bg-lagon-100")}>
                <Icon aria-hidden className="size-[22px]" />
                {count > 0 && (
                  <span className="tabular absolute -top-0.5 right-1.5 min-w-5 rounded-full bg-lagon-600 px-1 text-center text-[12px] leading-5 text-white">{count}</span>
                )}
              </span>
              {label}
              {count > 0 && <span className="sr-only">{sr}</span>}
            </>
          )}
        </NavLink>
      ))}
    </nav>
  );
}

export function Page({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  return (
    <>
      <header className="flex flex-col gap-1.5 px-4 pt-5 pb-2">
        <h1 className="m-0 font-display text-[24px] leading-[30px] font-semibold">{title}</h1>
        {subtitle && <p className="m-0 text-[16px] text-ink-muted">{subtitle}</p>}
      </header>
      <div className="flex grow flex-col gap-5 px-4 pt-3 pb-6">{children}</div>
    </>
  );
}
