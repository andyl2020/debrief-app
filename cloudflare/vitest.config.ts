import path from "node:path";
import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-pool-workers";
import { defineConfig } from "vitest/config";

process.env.BOOTSTRAP_SECRET ??= "test-bootstrap-secret-with-enough-entropy";
process.env.TOKEN_PEPPER ??= "test-token-pepper-with-enough-entropy";
process.env.PIN_PEPPER ??= "test-pin-pepper-with-enough-entropy";

export default defineConfig({
  plugins: [
    cloudflareTest(async () => ({
      wrangler: { configPath: "./wrangler.jsonc" },
      miniflare: {
        bindings: {
          BOOTSTRAP_SECRET: "test-bootstrap-secret-with-enough-entropy",
          TOKEN_PEPPER: "test-token-pepper-with-enough-entropy",
          PIN_PEPPER: "test-pin-pepper-with-enough-entropy",
          TEST_MIGRATIONS: await readD1Migrations(path.join(import.meta.dirname, "migrations")),
        },
      },
    })),
  ],
  test: {
    setupFiles: ["./test/apply-migrations.ts"],
  },
});
