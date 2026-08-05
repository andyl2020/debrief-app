import type { Env as AppEnv } from "../src/types";
import type { D1Migration } from "cloudflare:test";

declare global {
  namespace Cloudflare {
    interface Env extends AppEnv {
      TEST_MIGRATIONS: D1Migration[];
    }
  }
}

declare module "cloudflare:test" {
  interface ProvidedEnv extends AppEnv {
    TEST_MIGRATIONS: D1Migration[];
  }
}
