import { apiBlob } from "./api";

describe("fichiers reçus du serveur", () => {
  afterEach(() => vi.restoreAllMocks());

  it("acceptent tout format, pour les routes qui ne servent que du PDF", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response("%PDF", { status: 200, headers: { "Content-Disposition": "inline; filename=\"cle-de-secours.pdf\"" } }),
    );

    const { fileName } = await apiBlob("/setup/recovery-key/pdf", undefined, false);

    const init = fetchSpy.mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>).Accept).toBe("*/*");
    expect(fileName).toBe("cle-de-secours.pdf");
  });
});
