import "@testing-library/jest-dom/vitest";
import { cleanup, configure } from "@testing-library/react";

// Attentes « findBy » et « waitFor » : 3 s au lieu de 1 s, pour qu’un poste chargé ne fasse pas échouer un test juste.
configure({ asyncUtilTimeout: 3000 });

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  window.localStorage.clear();
});
