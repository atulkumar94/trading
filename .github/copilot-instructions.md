# Copilot Instructions

These instructions guide all AI assistants (Claude, GitHub Copilot, etc.) working on this repository.
They establish consistency across the codebase and ensure focused, purposeful development.

## Read the README first

All work begins with understanding the project's data flow. Refer to `../README.md` (repo root) for:
- Project purpose and end-to-end pipeline
- Directory structure and file roles
- Quick start commands
- Key concepts (rotation strategy, daily snapshots, eligibility rules)

When you are asked a question or given a task:
1. Check if `README.md` already explains it
2. Reference `README.md` when providing explanations to the user
3. Update `README.md` if you discover undocumented behavior

## Focus: data flow, not features

This is a data pipeline project. Our goal is:

- **Reliable data download** (Python: yfinance → CSVs)
- **Consistent data transformation** (Java: load → normalize → filter → snapshot)
- **Accurate backtest reporting** (Java: momentum ranking → P&L → CSV export)

**NOT our goal:**
- Building a trading execution system
- Supporting real-time data feeds
- Optimizing strategy parameters (parameter tuning is manual; engine just runs the backtest)
- Building a web interface or dashboard (exception: the generated, read-only, offline
  `rotation_portal.html` report, which only displays engine outputs; no backend, server, live data or trading)

If a request conflicts with this focus, ask the user to clarify alignment with the core data pipeline.

## Code standards

### Python (download_nse_data.py)
- Use type hints on all functions
- Validate input at the entry point (CSV parsing, symbol deduplication)
- Fail loudly on data integrity issues (e.g., duplicate downloads)
- No external dependencies beyond `pandas` and `yfinance`

### Java (rotation-engine/)
- All models are immutable (use `final` fields, no setters)
- Keep `Main.java` as an orchestration entry point; do not add business logic there
- Loaders return models; exporters write files; logic lives in the engine
- One responsibility per class (separation of concerns)
- Write tests for complex logic (e.g., ranking, P&L calculation)

## File naming & locations

**Do NOT create files in random locations.** Use these conventions:

- **Config:** `rotation-engine/config/*.properties`
- **Source code:** `rotation-engine/src/main/java/com/rotation/`
- **Tests:** `rotation-engine/src/test/java/com/rotation/`
- **Data input:** `stocks/daily/*.csv`
- **Data output:** `rotation-engine/output/rotation/*.csv`
- **Documentation:** `README.md`, `rotation-engine/README.md`, `.github/copilot-instructions.md`, `.claude/CLAUDE.md`

## When to edit what

### Edit README.md when:
- Adding a new command or workflow
- Documenting a new output CSV
- Explaining a new configuration option
- The user asks for an overview of "how to do X"

### Edit config/rotation.properties when:
- User wants to change data source, date range, strategy parameters, or output location
- Adding a new configurable parameter (always document in README.md too)

### Edit Java code when:
- Fixing bugs in core logic
- Adding a new data loader or exporter
- Improving performance
- User explicitly asks for a feature

### Edit download_nse_data.py when:
- Fixing download failures or data quality issues
- Adding command-line options (always document in Python --help)
- Changing retry or rate-limit behavior

## Data integrity checks

Before finalizing any code:

1. **Python downloads:** verify no duplicate data across symbol files (check `verify_no_duplicates()`)
2. **Java data loading:** ensure all symbols have matching date ranges
3. **CSV exports:** check row counts against expectations (dimension mismatch = bug)
4. **Date filters:** confirm start/end dates are actually applied (don't silently ignore invalid dates)

## Commit message format

Commits should follow this pattern:

```
<type>: <description>

<optional detailed explanation>
```

**Types:**
- `feat:` new feature or command
- `fix:` bug fix in core logic
- `docs:` README, comments, configuration docs
- `refactor:` improve code quality without changing behavior
- `test:` add or fix tests

**Examples:**
```
fix: handle missing columns in yfinance response

docs: update README with new output CSV format
```

## Questions to ask before coding

If a request is vague or misaligned, ask:

1. **Does this change the data flow?** (If yes, update README.md first)
2. **Is this in scope?** (Execution engine only; no strategy optimization or UI)
3. **What file is affected?** (Direct the user to the right file)
4. **How will this be tested?** (For Java, write a test; for Python, run download with sample data)

## When to defer to the user

- **Strategy parameter tuning:** users adjust `config/rotation.properties`; engine just runs it
- **Data quality decisions:** users decide which symbols to include in `symbols.csv`
- **Report interpretation:** users analyze output CSVs; engine just generates them

Do not assume what the user wants. Ask clarifying questions if the request is ambiguous.

## Code review checklist

Before marking work as "done":

- [ ] README.md updated if behavior changed
- [ ] No hard-coded absolute paths (relative config paths resolve from `rotation-engine/`)
- [ ] Error messages are clear and actionable
- [ ] No external dependencies added without user consent
- [ ] Tests pass (if applicable)
- [ ] CSV output row counts match expectations

## Consistency across the codebase

Maintain alignment:
- `README.md` describes the data flow
- `config/rotation.properties` is the single config source
- `Main.java` orchestrates; doesn't contain logic
- All loaders and exporters follow the same pattern
- All tests cover the happy path and edge cases

If you see inconsistency, fix it and mention it in the commit message.
