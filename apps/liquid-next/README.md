# Liquid Next Prototype

`apps/liquid-next` is a local design prototype for visual exploration only.

It is not part of the production release scope, is not built by `docker-compose.prod.yml`, and should not be deployed as a customer-facing service. Production storefront traffic belongs to `apps/web` and `apps/h5`.

For local exploration:

```bash
npm install
npm run dev -- --port 5177
```

From the repository root, `npm run dev:liquid` delegates into this folder. The prototype keeps its own `package-lock.json` and is intentionally not listed as a root workspace, so production install, audit, and Compose builds stay scoped to the release clients.
