import { parseSse } from "./stream";

describe("lecture du flux SSE", () => {
  it("découpe les événements complets et garde le reste", () => {
    const { events, rest } = parseSse(
      'event:ratio\ndata:{"global":{"status":"LIMITE"}}\n\nevent:ping\ndata:""\n\nevent:attendance\ndata:{"at"',
    );
    expect(events).toEqual([
      { name: "ratio", data: { global: { status: "LIMITE" } } },
      { name: "ping", data: "" },
    ]);
    expect(rest).toBe('event:attendance\ndata:{"at"');
  });

  it("accepte les fins de ligne CRLF et les données sur plusieurs lignes", () => {
    const { events } = parseSse('event:alert\r\ndata:{"started":\r\ndata:true}\r\n\r\n');
    expect(events).toEqual([{ name: "alert", data: { started: true } }]);
  });
});
