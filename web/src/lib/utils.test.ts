import { cn } from "./utils";

describe("cn", () => {
  it("garde la taille du design system à côté d’une couleur", () => {
    expect(cn("m-0 text-numeric-lg tabular-nums", "text-danger-700")).toBe("m-0 text-numeric-lg tabular-nums text-danger-700");
    expect(cn("text-small", "text-label")).toBe("text-label");
  });
});
