# Mengwei Trade Workbench MVP

A local-first foreign trade workbench for export teams that need three things fast:

- a reusable company and market profile
- first-pass content assets for inquiry replies and market pages
- a lightweight leads desk with visible follow-up priority

This MVP is intentionally small. It turns the original "local leads workbench" idea into an export-oriented operating desk without trying to ship the whole automation stack on day one.

## What is in the MVP

- `Command Center`
  - editable company profile
  - target markets, buyer types, trust signals, certifications, offer, and response SLA
  - readiness cards for market and proof coverage
- `Content Studio`
  - generated draft blocks for:
    - inquiry reply
    - distributor outreach email
    - landing page hero
    - GEO / SEO FAQ block
- `Leads Desk`
  - manual lead intake
  - local scoring based on value, urgency, and stage
  - simple pipeline board for `new -> qualified -> quoted -> sampling -> won`
- `Next Sprint`
  - suggested path for AI, CRM sync, export GEO publishing, and inbox monitoring

## Stack

- Vue 3
- TypeScript
- Vite

## Local run

```bash
npm install
npm run dev
```

## Build

```bash
npm run build
```

## Product direction

This repo is meant to be the MVP shell for a larger foreign trade system. The natural next steps are:

1. connect an OpenAI-compatible API for real content generation
2. replace demo lead scoring with rules plus AI-assisted qualification
3. add website / country page publishing workflows
4. sync qualified leads into a CRM
