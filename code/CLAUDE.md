# QE / Test Planning Philosophy

When helping design or expand test cases (e.g. the "Live Updates Test Cases" sheet), follow
this prioritization instead of building exhaustive matrices:

- **Untouched, pre-tested code paths**: skip deep re-testing. Run one sanity/smoke scenario
  to confirm no regression, and move on.
- **Consolidate into "big tests"**: prefer fewer test runs that each exercise multiple related
  assertions in one flow, over one test per individual case. If several inputs hit the exact
  same code path (e.g. type-coercion fallback), one representative case validates the whole
  family — don't enumerate every variant.
- **Closely related scenarios**: test 1-2 representative ones; skip the rest if the first is
  confirmed safe/behaves as expected.
- **Coverage goal is breadth across feature sets, not exhaustiveness.** Covering every
  possible test is not the goal — covering every feature set/code path at least once is.
- Only go deep (multiple sub-cases) on genuinely new/changed logic, or where a single input
  could plausibly branch to a different code path (e.g. a value that gets coerced/accepted
  vs one that gets rejected) — that fork is worth its own case; equivalent rejections are not.

## Live Updates Test Cases sheet

- Sheet: `Live Updates Test Cases.xlsx` (SharePoint, owned by harjots), tabs: `Local`,
  `Transactional - Staging & Prod`, `Broadcast`.
- Per Navratan: add a row whenever you personally test an SDK or "Cepheus + SDK" scenario
  that isn't already a row in the sheet — don't pre-write speculative rows, only log tests
  actually run with observed results.
- Timestamp-related transactional testing should be done on the **va7** sandbox (per Harjot),
  not va6.
