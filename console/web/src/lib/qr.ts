import qrcode from "qrcode-generator";

/** Image du QR code d'un texte, sans appel réseau (même bibliothèque que le poste bureau, ADR 0008). */
export function qrDataUrl(text: string): string {
  const qr = qrcode(0, "M");
  qr.addData(text);
  qr.make();
  return qr.createDataURL(6, 2);
}
