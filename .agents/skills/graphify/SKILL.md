---
name: graphify
description: Knowledge graph navigator and codebase structural context retriever using Graphify. Activate when working on complex architectural queries, tracing multi-hop dependencies across Kotlin/Android components, or when graphify-out/ is present in the workspace.
---

# Graphify Knowledge Graph Skill for Antigravity

This skill enables Antigravity to utilize Graphify knowledge graphs to rapidly navigate, query, and trace relationships within this project (`NovelScraper2`).

## Available Artifacts
Graphify builds the knowledge graph in `graphify-out/`:
- `graphify-out/graph.json`: Structural graph of classes, functions, and file relationships.
- `graphify-out/GRAPH_REPORT.md`: Architectural summary and key god nodes.
- `graphify-out/graph.html`: Interactive visual graph (for browser viewing).

## How Antigravity Uses Graphify
1. **Navigating Architecture**: Read `graphify-out/GRAPH_REPORT.md` first to understand core modules, key data models, and entry points.
2. **Tracing Multi-Hop Dependencies**: Inspect `graphify-out/graph.json` to find function calls, class inheritances, or module connections across the app.
3. **Updating the Graph**: The graph auto-updates via git post-commit hook. Do not manually run `graphify .` — commit your changes normally and the graph refreshes automatically.
