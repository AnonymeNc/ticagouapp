import { ApiError } from "@/lib/api";
import type { Operation, OperationResult } from "./api";
import { memoryStore, Outbox } from "./outbox";

const op = (clientId: string, type: Operation["type"] = "ARRIVEE"): Operation => ({
  clientId,
  type,
  occurredAt: "2026-11-02T07:42:00Z",
  offline: false,
  userId: "u-2",
  childId: "c-1",
});

const applied = (o: Operation): OperationResult => ({ clientId: o.clientId, outcome: "APPLIQUEE", code: null, message: null, needsReview: false });

describe("file d’attente de la tablette", () => {
  afterEach(() => vi.useRealTimers());

  it("envoie dans l’ordre et renvoie le résultat de chaque opération", async () => {
    const sent: string[][] = [];
    const outbox = new Outbox(memoryStore(), async (ops) => {
      sent.push(ops.map((o) => o.clientId));
      return { results: ops.map(applied) };
    });
    await outbox.load();

    expect((await outbox.enqueue(op("a")))?.outcome).toBe("APPLIQUEE");
    expect((await outbox.enqueue(op("b", "CONSIGNES")))?.outcome).toBe("APPLIQUEE");
    expect(sent).toEqual([["a"], ["b"]]);
    expect(outbox.getState().pending).toEqual([]);
    expect(outbox.getState().acked.map((a) => a.op.clientId)).toEqual(["a", "b"]);
    expect(outbox.getState().lastSyncAt).not.toBeNull();
  });

  it("garde la file hors connexion puis la renvoie en entier au retour du serveur", async () => {
    vi.useFakeTimers();
    let up = false;
    const sent: string[][] = [];
    const store = memoryStore();
    const outbox = new Outbox(store, async (ops) => {
      if (!up) throw new TypeError("Failed to fetch");
      sent.push(ops.map((o) => o.clientId));
      return { results: ops.map(applied) };
    });

    expect(await outbox.enqueue(op("a"))).toBeNull();
    expect(await outbox.enqueue(op("b"))).toBeNull();
    expect(outbox.getState()).toMatchObject({ online: false });
    expect((await store.all()).map((o) => o.clientId)).toEqual(["a", "b"]);

    up = true;
    await vi.advanceTimersByTimeAsync(5_000);
    expect(sent).toEqual([["a", "b"]]);
    expect(outbox.getState()).toMatchObject({ online: true, pending: [] });
    expect(await store.all()).toEqual([]);
  });

  it("recharge les opérations restées sur la tablette", async () => {
    const sent: string[] = [];
    const outbox = new Outbox(memoryStore([op("x"), op("y")]), async (ops) => {
      sent.push(...ops.map((o) => o.clientId));
      return { results: ops.map(applied) };
    });
    await outbox.load();
    await outbox.flush();
    expect(sent).toEqual(["x", "y"]);
  });

  it("remonte les opérations refusées sans bloquer les suivantes", async () => {
    const outbox = new Outbox(memoryStore(), async (ops) => ({
      results: ops.map((o) =>
        o.clientId === "a"
          ? { clientId: "a", outcome: "REFUSEE", code: "not_present", message: "Pas pointé présent.", needsReview: false }
          : applied(o),
      ),
    }));
    expect((await outbox.enqueue(op("a", "DEPART")))?.outcome).toBe("REFUSEE");
    expect((await outbox.enqueue(op("b")))?.outcome).toBe("APPLIQUEE");
    expect(outbox.getState().rejected.map((r) => r.result.message)).toEqual(["Pas pointé présent."]);
    outbox.dismiss("a");
    expect(outbox.getState().rejected).toEqual([]);
  });

  it("écarte une opération mal formée (400) pour ne pas bloquer la file", async () => {
    let calls = 0;
    const outbox = new Outbox(memoryStore(), async (ops) => {
      calls++;
      if (ops[0].clientId === "bad") throw new ApiError(400, "validation", "Opération invalide.");
      return { results: ops.map(applied) };
    });
    expect((await outbox.enqueue(op("bad")))?.outcome).toBe("REFUSEE");
    expect((await outbox.enqueue(op("ok")))?.outcome).toBe("APPLIQUEE");
    expect(calls).toBe(2);
  });

  it("oublie les opérations confirmées une fois intégrées à l’instantané", async () => {
    const outbox = new Outbox(memoryStore(), async (ops) => ({ results: ops.map(applied) }));
    await outbox.enqueue(op("a"));
    outbox.forgetAckedBefore(Date.now() - 60_000);
    expect(outbox.getState().acked).toHaveLength(1);
    outbox.forgetAckedBefore(Date.now() + 1);
    expect(outbox.getState().acked).toHaveLength(0);
  });
});
