HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier, default, no override)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 10 repair round 1, documentation part (MINOR-6, 7, 8; the Operation cardinality note; MODULE.md; STATUS.md repair line).
FILES CREATED: none
FILES MODIFIED: docs/api.md, modules/services/MODULE.md, STATUS.md
COMMANDS RUN: grep over tests/module-unit/services for the cited tests; Python edit script; awk line-length check; grep for em dashes (0 found).
TEST RESULTS: none run (docs only, no sbt, as ordered).
VALIDATION RESULTS:
- MINOR-6: docs/api.md now says a failed job returns 200 with state failed and an error object on GET /v1/jobs/{id}. Only /result returns the typed status (502 dependency_failed). Test: "a failed asynchronous job reports the typed error on the job and its result" (QueryServiceSuite:352).
- MINOR-7: the hunter2 case now names ServiceKitSuite "a typed error maps to its status; an unexpected exception is a generic 500 that leaks nothing" (CommonSuite.scala:224-231).
- MINOR-8: no asserting test exists for Retry-After, BudgetExceeded->422, pageSize default 20 or the 200 bound (the bound test uses maxResults=5). All four are now marked "code fact, not asserted", and the intro defines the term. Unsupported->422 is asserted (QueryServiceSuite:216) and is stated as such. The pageSize max of 50 is stated with its 51 and 0 -> 422 test.
- Operation note (api.md, Metrics section): allowlisted prefixes plus the single value "unmatched". Test: "unmatched paths share one constant EMF Operation" (CommonSuite:299).
- MODULE.md: added a repair round 1 entry to Done (eviction, pending reservation before store.add, allowlisted Operation, 4 tests added, per-project totals not observed). Added MINOR-1 to 5, no capacity in /readyz and evicted-key replay re-running as Known issues.
- STATUS.md: added a repair round 1 line to the Phase 10 section. It names the services count as 73 before repair, with 4 tests added since.
- Checks: no em dashes. Lines over 90 characters in the three files are only older table rows and headers, not mine.
ASSUMPTIONS: The reviewer's "ServiceKitSuite" is the class inside CommonSuite.scala, which I confirmed.
RISKS: The services project test totals after repair are not documented, because service-engineer did not observe them. STATUS.md "Next step" and "Verify" lines still say 73; only the new repair line mentions the added tests. I did not replay sbt. Nothing is committed.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: fresh re-check (review-report-02.md).
