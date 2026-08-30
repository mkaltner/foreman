import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";
import { loadForemanBuildMetadata } from "./build-metadata";

const repositoryRoot = fileURLToPath(new URL("..", import.meta.url));
const buildMetadata = loadForemanBuildMetadata(repositoryRoot);

export default defineConfig({
  plugins: [react()],
  define: {
    __FOREMAN_CLIENT_VERSION__: JSON.stringify(buildMetadata.version),
    __FOREMAN_CLIENT_COMMIT__: JSON.stringify(buildMetadata.commit),
  },
  server: {
    host: "0.0.0.0",
    port: 5173,
  },
  test: {
    environment: "jsdom",
    setupFiles: "./src/test-setup.ts",
  },
});
