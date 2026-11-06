/**
 * Flux temps réel de l'encadrement (SSE, ADR 0005), lu par fetch : EventSource ne sait pas envoyer
 * l'en-tête Authorization. Reconnexion automatique après une coupure.
 */
import { useEffect, useRef } from "react";
import { apiStream } from "@/lib/api";

export type StreamEvent = { name: string; data: unknown };

/** Découpe un tampon SSE en événements complets ; renvoie le reste (événement incomplet). */
export function parseSse(buffer: string): { events: StreamEvent[]; rest: string } {
  const blocks = buffer.replace(/\r\n/g, "\n").split("\n\n");
  const rest = blocks.pop() ?? "";
  const events: StreamEvent[] = [];
  for (const block of blocks) {
    let name = "message";
    const data: string[] = [];
    for (const line of block.split("\n")) {
      if (line.startsWith("event:")) name = line.slice(6).trim();
      else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
    }
    if (data.length === 0) continue;
    const text = data.join("\n");
    try {
      events.push({ name, data: text === "" ? null : JSON.parse(text) });
    } catch {
      events.push({ name, data: text });
    }
  }
  return { events, rest };
}

const RETRY_MS = 5_000;

/**
 * Abonnement au flux de la structure. {@code auth} : "device" sur la tablette, true au bureau.
 * Les événements « ping » ne sont pas transmis.
 */
export function useStaffingStream(options: {
  auth: true | "device";
  enabled: boolean;
  onEvent: (event: StreamEvent) => void;
  onStatus?: (connected: boolean) => void;
}) {
  const handlers = useRef(options);
  handlers.current = options;
  const { auth, enabled } = options;

  useEffect(() => {
    if (!enabled) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | null = null;

    const connect = async () => {
      try {
        const response = await apiStream("/staffing/stream", { signal: controller.signal, auth });
        const reader = response.body?.getReader();
        if (!reader) throw new Error("flux indisponible");
        handlers.current.onStatus?.(true);
        const decoder = new TextDecoder();
        let buffer = "";
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          const parsed = parseSse(buffer);
          buffer = parsed.rest;
          parsed.events.filter((e) => e.name !== "ping").forEach((e) => handlers.current.onEvent(e));
        }
      } catch {
        // Coupure, serveur arrêté : nouvelle tentative plus bas.
      }
      if (controller.signal.aborted) return;
      handlers.current.onStatus?.(false);
      timer = setTimeout(connect, RETRY_MS);
    };
    void connect();
    return () => {
      controller.abort();
      if (timer) clearTimeout(timer);
    };
  }, [auth, enabled]);
}
