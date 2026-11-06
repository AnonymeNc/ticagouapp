import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "./index.css";

// Service worker en production seulement (contexte sécurisé : le portail est servi en HTTPS).
if (import.meta.env.PROD && "serviceWorker" in navigator && window.isSecureContext) {
  void navigator.serviceWorker.register("/sw.js").catch(() => {});
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
