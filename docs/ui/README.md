# Vendored Antora UI bundle

`ui-bundle.zip` is the stock Antora default UI, vendored so the documentation build is reproducible and does not
depend on a rolling download. Antora only distributes the default UI as a rolling `HEAD` build; this is a pinned copy.

| | |
|---|---|
| Source | https://gitlab.com/antora/antora-ui-default/-/jobs/15385706035/artifacts/raw/build/ui-bundle.zip |
| Resolved from | https://gitlab.com/antora/antora-ui-default/-/jobs/artifacts/HEAD/raw/build/ui-bundle.zip?job=bundle-stable |
| Downloaded | 2026-11-01 |
| Bundle build date | 2026-07-16 (timestamps inside the archive) |
| SHA-256 | `e0ac81aad26961a9cd3cf9bce692a598eff4a1bd2822ce73c9cdc13a15a1e70d` |

The playbook (`../antora-playbook.yml`) points at it with `ui.bundle.url: ./ui/ui-bundle.zip`.

## Updating

1. Download the new bundle into an empty directory and check that it is a zip containing `css/`, `js/` and
   `layouts/`. Do not run anything from it.
2. Replace `ui-bundle.zip`, then update the table above (job URL, date, SHA-256 from `sha256sum ui-bundle.zip`).
3. Build the site (`cd docs && npx antora antora-playbook.yml`) and check it renders.
