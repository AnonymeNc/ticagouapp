import { ageInMonths, formatAge, todayIso } from "./age";
import { fileNameFrom } from "./api";

describe("âge des enfants", () => {
  it("compte les mois révolus", () => {
    expect(ageInMonths("2024-07-22", "2026-10-31")).toBe(27);
    expect(ageInMonths("2024-07-22", "2026-10-21")).toBe(26);
    expect(ageInMonths("2026-10-01", "2026-10-31")).toBe(0);
  });

  it("formate en années et mois", () => {
    expect(formatAge(27)).toBe("2 ans 3 mois");
    expect(formatAge(8)).toBe("8 mois");
    expect(formatAge(12)).toBe("1 an");
    expect(formatAge(36)).toBe("3 ans");
  });

  it("prend la date du jour à Nouméa", () => {
    // 31/10 à 13:30 UTC = 01/11 à 00:30 à Nouméa
    expect(todayIso(new Date("2026-10-31T13:30:00Z"))).toBe("2026-11-01");
  });
});

describe("nom de fichier proposé par le serveur", () => {
  it("décode le nom UTF-8", () => {
    expect(fileNameFrom("attachment; filename=\"PAI_No=.pdf\"; filename*=UTF-8''PAI%20No%C3%A9.pdf")).toBe("PAI Noé.pdf");
    expect(fileNameFrom('attachment; filename="export.zip"')).toBe("export.zip");
    expect(fileNameFrom(null)).toBeNull();
  });
});
