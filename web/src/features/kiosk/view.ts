/**
 * État affiché par la tablette : instantané du serveur, puis opérations de la file appliquées par-dessus.
 * Chaque application est idempotente : une opération déjà intégrée à l'instantané ne change rien.
 */
import type { InstructionInput, KioskAttendance, KioskChild, KioskStaff, KioskToday, Operation } from "./api";
import { load, type Load } from "./ratio";

const clone = <T,>(value: T): T => structuredClone(value);

export function applyOperations(
  today: KioskToday,
  ops: { op: Operation; pending: boolean }[],
  names: Map<string, string>,
): KioskToday {
  if (ops.length === 0) return today;
  const next = clone(today);
  for (const { op, pending } of ops) {
    apply(next, op, pending, names);
  }
  return next;
}

function apply(today: KioskToday, op: Operation, pending: boolean, names: Map<string, string>) {
  const child = op.childId ? today.children.find((c) => c.id === op.childId) : undefined;
  const author = (op.userId && names.get(op.userId)) ?? "";
  switch (op.type) {
    case "ARRIVEE": {
      if (!child || openAttendance(child) || child.attendances.some((a) => a.id === op.attendanceId)) return;
      child.attendances.push({
        id: op.attendanceId ?? op.clientId,
        arrivedAt: op.occurredAt,
        departedAt: null,
        droppedOffBy: null,
        pickedUpByName: null,
        instructions: [],
        pending,
      });
      return;
    }
    case "ANNULER_ARRIVEE": {
      if (!child) return;
      const target = findAttendance(child, op.attendanceId);
      if (target && !target.departedAt) child.attendances = child.attendances.filter((a) => a !== target);
      return;
    }
    case "CONSIGNES": {
      const target = child && findAttendance(child, op.attendanceId);
      if (!target) return;
      target.droppedOffBy = op.droppedOffBy ?? null;
      const given = target.instructions.filter((i) => i.givenAt);
      target.instructions = [
        ...given,
        ...(op.instructions ?? []).filter((i) => !given.some((g) => g.id === i.id)).map(toInstruction),
      ];
      if (pending) target.pending = true;
      return;
    }
    case "MEDICAMENT_DONNE": {
      for (const c of today.children) {
        for (const a of c.attendances) {
          const instruction = a.instructions.find((i) => i.id === op.instructionId);
          if (instruction && !instruction.givenAt) {
            instruction.givenAt = op.occurredAt;
            instruction.givenBy = author;
          }
        }
      }
      return;
    }
    case "DEPART": {
      const target = child && findAttendance(child, op.attendanceId);
      if (!child || !target || target.departedAt) return;
      target.departedAt = op.occurredAt;
      target.pickedUpByName =
        op.override?.personName ?? child.pickup.find((p) => p.id === op.pickup?.personId)?.name ?? null;
      if (pending) target.pending = true;
      return;
    }
    case "POINTAGE_PERSONNEL": {
      const staff = today.staff.find((s) => s.userId === op.userId);
      if (!staff) return;
      const open = openStaffAttendance(staff);
      if (op.staffAction === "ARRIVEE") {
        if (open || staff.attendances.some((a) => a.id === (op.attendanceId ?? op.clientId))) return;
        staff.attendances.push({
          id: op.attendanceId ?? op.clientId,
          arrivedAt: op.occurredAt,
          departedAt: null,
          sectionId: op.sectionId ?? staff.homeSectionId,
          pending,
        });
      } else if (open) {
        open.departedAt = op.occurredAt;
        if (pending) open.pending = true;
      }
      return;
    }
    case "SECTION_PERSONNEL": {
      const staff = today.staff.find((s) => s.userId === op.staffUserId);
      const open = staff && openStaffAttendance(staff);
      if (open && op.sectionId) open.sectionId = op.sectionId;
      return;
    }
    case "CONNEXION_HORS_LIGNE":
    case "TRANSMISSION":
      return;
  }
}

function toInstruction(input: InstructionInput) {
  return {
    id: input.id,
    kind: input.kind,
    details: input.details ?? null,
    medicationAt: input.medicationAt ?? null,
    medicationDose: input.medicationDose ?? null,
    prescriptionGiven: input.prescriptionGiven ?? false,
    givenAt: null,
    givenBy: null,
  };
}

function findAttendance(child: KioskChild, attendanceId?: string): KioskAttendance | undefined {
  return child.attendances.find((a) => a.id === attendanceId) ?? openAttendance(child);
}

export function openAttendance(child: KioskChild): KioskAttendance | undefined {
  return child.attendances.find((a) => !a.departedAt);
}

export function lastAttendance(child: KioskChild): KioskAttendance | undefined {
  return child.attendances.at(-1);
}

export function openStaffAttendance(staff: KioskStaff) {
  return staff.attendances.find((a) => !a.departedAt);
}

export type ChildStatus = "present" | "parti" | "retard" | "attendu" | "hors";

/** Un enfant attendu est « en retard » 15 minutes après l'heure prévue (comme au bureau). */
export function childStatus(child: KioskChild, nowMinutes: number): ChildStatus {
  if (openAttendance(child)) return "present";
  if (child.attendances.length > 0) return "parti";
  if (!child.planned || !child.expectedAt) return "hors";
  return nowMinutes > minutes(child.expectedAt) + 15 ? "retard" : "attendu";
}

export function minutes(time: string) {
  const [h, m] = time.split(":").map(Number);
  return h * 60 + m;
}

/** Enfants de la grille : attendus aujourd'hui ou déjà passés. */
export function gridChildren(today: KioskToday) {
  return today.children.filter((c) => c.planned || c.attendances.length > 0);
}

export type RatioView = { global: Load; sections: Map<string, Load> };

export function ratioView(today: KioskToday): RatioView {
  const adults = today.staff.flatMap((s) => {
    const open = openStaffAttendance(s);
    return s.countsInRatio && open ? [open.sectionId] : [];
  });
  const present = today.children.filter((c) => openAttendance(c));
  const sections = new Map<string, Load>();
  for (const section of today.sections) {
    const ratios = present.filter((c) => c.sectionId === section.id).map((c) => c.childrenPerAdult);
    sections.set(section.id, load(ratios, adults.filter((a) => a === section.id).length, today.fallbackChildrenPerAdult));
  }
  return {
    global: load(present.map((c) => c.childrenPerAdult), adults.length, today.fallbackChildrenPerAdult),
    sections,
  };
}
