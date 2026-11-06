import {
  addDays,
  daysBetween,
  formatDays,
  formatMinutes,
  formatMinutesDelta,
  formatMonthLong,
  formatMonthShort,
  formatVisit,
  formatXpf,
  isoWeekday,
  nextMonths,
  span,
} from "./format";

describe("formats d'affichage", () => {
  it("formate les francs CFP sans décimale", () => {
    expect(formatXpf(78901)).toBe("78 901 F");
    expect(formatXpf(620)).toBe("620 F");
    expect(formatXpf(1234567)).toBe("1 234 567 F");
    expect(formatXpf(-28000)).toBe("− 28 000 F");
  });

  it("formate les durées", () => {
    expect(formatMinutes(2370)).toBe("39 h 30");
    expect(formatMinutes(540)).toBe("9 h 00");
    expect(formatMinutesDelta(240)).toBe("+ 4 h 00");
    expect(formatMinutesDelta(-90)).toBe("− 1 h 30");
    expect(span("07:30", "16:30")).toBe(540);
    expect(span("16:30", "07:30")).toBeLessThan(0);
  });

  it("formate les jours et les mois", () => {
    expect(formatDays([1, 2, 3, 4, 5])).toBe("Lun à ven");
    expect(formatDays([4, 1, 2])).toBe("Lun, mar, jeu");
    expect(formatMonthShort("2027-02")).toBe("Févr. 2027");
    expect(formatMonthLong("2027-01")).toBe("janvier 2027");
    expect(formatVisit("2026-11-05T09:30:00")).toBe("jeu. 05/11/2026 · 09:30");
  });

  it("calcule les mois et les jours", () => {
    expect(nextMonths(3, new Date("2026-11-15T00:00:00Z"))).toEqual(["2026-11", "2026-12", "2027-01"]);
    expect(daysBetween("2026-10-29", "2026-11-03")).toBe(5);
    expect(addDays("2027-01-29", 3)).toBe("2027-02-01");
    expect(isoWeekday("2027-02-01")).toBe(1);
    expect(isoWeekday("2027-01-31")).toBe(7);
  });
});
