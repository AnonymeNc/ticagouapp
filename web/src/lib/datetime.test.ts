import { formatDate, formatLongDate, formatTime, formatTopbarStamp, isoToDateFr, parseDateFr } from "./datetime";

// 29/10/2026 22:15 UTC = vendredi 30/10/2026 09:15 à Nouméa (UTC+11)
const instant = new Date("2026-10-29T22:15:00Z");

describe("dates au format calédonien", () => {
  it("formate en JJ/MM/AAAA dans le fuseau Pacific/Noumea", () => {
    expect(formatDate(instant)).toBe("30/10/2026");
  });

  it("formate l'heure sur 24 h", () => {
    expect(formatTime(instant)).toBe("09:15");
    expect(formatTime(new Date("2026-10-30T12:05:00Z"))).toBe("23:05");
  });

  it("formate la date longue avec majuscule", () => {
    expect(formatLongDate(instant)).toBe("Vendredi 30 octobre 2026");
  });

  it("formate l'horodatage de la barre supérieure", () => {
    expect(formatTopbarStamp(instant)).toBe("Ven. 30/10 · 09:15");
  });
});

describe("saisie de date JJ/MM/AAAA", () => {
  it("convertit en date ISO", () => {
    expect(parseDateFr("15/01/2024")).toBe("2024-01-15");
    expect(parseDateFr(" 1/2/2026 ")).toBe("2026-02-01");
  });

  it("refuse les dates impossibles ou mal formées", () => {
    expect(parseDateFr("31/02/2024")).toBeNull();
    expect(parseDateFr("2024-01-15")).toBeNull();
    expect(parseDateFr("15/13/2024")).toBeNull();
  });

  it("reformate une date ISO", () => {
    expect(isoToDateFr("2024-01-15")).toBe("15/01/2024");
  });
});
