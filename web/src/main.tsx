import "./index.css";
import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import { bootstrapNative } from "./lib/native/bootstrap";

// Application mobile : la crèche et la session sont relues avant le premier rendu (ADR 0024, 0025).
void bootstrapNative().finally(() => {
  ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>,
  );
});
