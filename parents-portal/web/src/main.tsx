import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import { portal } from "./api";
import { IS_APP, getSessionToken, keepMediaToken, loadAppSession } from "./native";
import "./index.css";

async function start() {
  if (IS_APP) {
    // Application (ADR 0024) : session du trousseau, puis jeton des photos et fichiers.
    await loadAppSession();
    if (getSessionToken()) await portal.refreshMediaToken().catch(() => {});
    keepMediaToken(portal.refreshMediaToken);
  } else if (import.meta.env.PROD && "serviceWorker" in navigator && window.isSecureContext) {
    // Service worker en production seulement (contexte sécurisé : le portail est servi en HTTPS).
    void navigator.serviceWorker.register("/sw.js").catch(() => {});
  }
  createRoot(document.getElementById("root")!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

void start();
