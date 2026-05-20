# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root, or
- **`CONTEXT-MAP.md`** at the repo root if it exists; it points at one `CONTEXT.md` per context. Read each one relevant to the topic.
- **`docs/adr/`**; read ADRs that touch the area you're about to work in.

If any of these files don't exist, proceed silently. The producer skill (`/grill-with-docs`) creates them lazily when terms or decisions actually get resolved.

## File structure

This repo uses the single-context layout by default:

```text
/
├── CONTEXT.md
├── docs/adr/
└── src/
```

## Use the glossary's vocabulary

When your output names a domain concept, use the term as defined in `CONTEXT.md`. If the concept you need is missing, note it for `/grill-with-docs` rather than inventing conflicting vocabulary.

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding it.
