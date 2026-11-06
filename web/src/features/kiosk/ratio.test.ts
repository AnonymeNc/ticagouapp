import { load } from "./ratio";

const times = (n: number, ratio: number) => Array.from({ length: n }, () => ratio);

describe("taux d'encadrement sur la tablette", () => {
  it("retrouve les valeurs du tableau de bord", () => {
    expect(load(times(9, 5), 2, 5)).toEqual({ children: 9, adults: 2, required: 2, coveredPlaces: 10, status: "LIMITE" });
    expect(load(times(12, 8), 1, 5)).toEqual({ children: 12, adults: 1, required: 2, coveredPlaces: 8, status: "INSUFFISANT" });
    expect(load(times(12, 8), 2, 5)).toEqual({ children: 12, adults: 2, required: 2, coveredPlaces: 16, status: "CONFORME" });
  });

  it("exactement au ratio : à la limite ; un enfant de plus : insuffisant", () => {
    expect(load(times(5, 5), 1, 5).status).toBe("LIMITE");
    expect(load(times(6, 5), 1, 5).status).toBe("INSUFFISANT");
  });

  it("arrondit au supérieur les tranches mêlées", () => {
    const mixed = [...times(3, 5), ...times(4, 8)];
    expect(load(mixed, 1, 5)).toMatchObject({ required: 2, status: "INSUFFISANT" });
    expect(load(mixed, 2, 5).status).toBe("CONFORME");
  });

  it("sans enfant, rien n'est requis", () => {
    expect(load([], 0, 5)).toEqual({ children: 0, adults: 0, required: 0, coveredPlaces: 0, status: "CONFORME" });
    expect(load([], 2, 5).coveredPlaces).toBe(10);
    expect(load(times(1, 8), 0, 5)).toMatchObject({ required: 1, status: "INSUFFISANT", coveredPlaces: 0 });
  });
});
