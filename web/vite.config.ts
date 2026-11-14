/// <reference types="vitest/config" />
import { fileURLToPath, URL } from "node:url";
import process from "node:process";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// Serveur Spring Boot en développement
const apiTarget = process.env.TICAGOU_API_URL ?? "http://localhost:8080";

// https://vite.dev/config/
export default defineConfig(() => ({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },

  server: {
    port: 1420,
    strictPort: true,
    proxy: {
      "/api": apiTarget,
    },
  },

  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    css: false,
    // Tests de formulaires longs (saisie simulée) : marge pour un poste chargé.
    testTimeout: 15_000,
  },
}));
