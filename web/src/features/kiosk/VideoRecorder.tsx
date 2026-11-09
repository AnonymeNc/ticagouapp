import { useEffect, useRef, useState } from "react";
import { Circle, RotateCcw, Square } from "lucide-react";
import { TouchButton } from "./components";

/** Vidéo de la journée (ADR 0019) : 30 s au plus, compressée à l'enregistrement. */
export const MAX_VIDEO_SECONDS = 30;
const VIDEO_BITS = 900_000;
const AUDIO_BITS = 64_000;
/** MP4 d'abord : lisible sur tous les téléphones des parents ; WebM sinon. */
const TYPES = ["video/mp4;codecs=avc1", "video/mp4", "video/webm;codecs=vp8,opus", "video/webm"];

export function videoSupported() {
  return typeof window !== "undefined" && "MediaRecorder" in window && !!navigator.mediaDevices?.getUserMedia;
}

function recorderType() {
  return TYPES.find((t) => MediaRecorder.isTypeSupported?.(t)) ?? "";
}

/**
 * Filme avec la caméra arrière de la tablette en 640×480 au plus, environ 1 Mbit/s : une vidéo de 30 s pèse 4 Mo.
 * L'enregistrement s'arrête seul au bout de 30 s.
 */
export function VideoRecorder({ video, onChange }: { video: Blob | null; onChange: (video: Blob | null) => void }) {
  const preview = useRef<HTMLVideoElement>(null);
  const stream = useRef<MediaStream | null>(null);
  const recorder = useRef<MediaRecorder | null>(null);
  const [recording, setRecording] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [url, setUrl] = useState<string | null>(null);

  const stopCamera = () => {
    stream.current?.getTracks().forEach((t) => t.stop());
    stream.current = null;
  };

  useEffect(() => () => stopCamera(), []);

  useEffect(() => {
    if (!video) {
      setUrl(null);
      return;
    }
    const next = URL.createObjectURL(video);
    setUrl(next);
    return () => URL.revokeObjectURL(next);
  }, [video]);

  useEffect(() => {
    if (!recording) return;
    if (elapsed >= MAX_VIDEO_SECONDS) {
      recorder.current?.stop();
      return;
    }
    const timer = setTimeout(() => setElapsed((s) => s + 1), 1000);
    return () => clearTimeout(timer);
  }, [recording, elapsed]);

  const start = async () => {
    setError(null);
    onChange(null);
    try {
      const media = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: "environment", width: { ideal: 640, max: 640 }, height: { ideal: 480, max: 480 } },
        audio: true,
      });
      stream.current = media;
      if (preview.current) {
        preview.current.srcObject = media;
        try {
          void Promise.resolve(preview.current.play()).catch(() => {});
        } catch {
          // Aperçu indisponible : l'enregistrement continue.
        }
      }
      const type = recorderType();
      const rec = new MediaRecorder(media, { ...(type ? { mimeType: type } : {}), videoBitsPerSecond: VIDEO_BITS, audioBitsPerSecond: AUDIO_BITS });
      const chunks: Blob[] = [];
      rec.ondataavailable = (e) => {
        if (e.data.size > 0) chunks.push(e.data);
      };
      rec.onstop = () => {
        setRecording(false);
        stopCamera();
        if (preview.current) preview.current.srcObject = null;
        onChange(new Blob(chunks, { type: (rec.mimeType || type || "video/webm").split(";")[0] }));
      };
      recorder.current = rec;
      setElapsed(0);
      rec.start(1000);
      setRecording(true);
    } catch {
      stopCamera();
      setError("La caméra n’est pas disponible : autorisez-la pour cette application, puis réessayez.");
    }
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="relative w-full max-w-xl overflow-hidden rounded-lg border border-border bg-black" style={{ aspectRatio: "4 / 3" }}>
        {url && !recording ? (
          <video src={url} controls playsInline aria-label="Vidéo enregistrée" className="size-full object-contain" />
        ) : (
          <video ref={preview} muted playsInline aria-label="Caméra" className="size-full object-cover" />
        )}
        {recording && (
          <span role="timer" aria-live="off" className="absolute top-3 left-3 flex items-center gap-2 rounded-full bg-danger-600 px-3 py-1 text-touch-label text-white tabular-nums">
            <Circle aria-hidden className="size-4 fill-current" />
            {String(elapsed).padStart(2, "0")} s / {MAX_VIDEO_SECONDS} s
          </span>
        )}
      </div>
      <div className="flex flex-wrap gap-3">
        {recording ? (
          <TouchButton variant="danger" onClick={() => recorder.current?.stop()}>
            <Square aria-hidden className="size-6" />Arrêter
          </TouchButton>
        ) : (
          <TouchButton variant={video ? "secondary" : "primary"} onClick={() => void start()}>
            {video ? <RotateCcw aria-hidden className="size-6" /> : <Circle aria-hidden className="size-6" />}
            {video ? "Refaire la vidéo" : "Filmer (30 s au plus)"}
          </TouchButton>
        )}
      </div>
      {error && <p role="alert" className="m-0 text-touch-label text-danger-700">{error}</p>}
    </div>
  );
}
