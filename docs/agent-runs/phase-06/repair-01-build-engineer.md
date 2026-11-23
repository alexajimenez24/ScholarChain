HARNESS: claude-code
MODEL: Sonnet 5.5 (standard tier, default)

STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Phase 06 repair round 1, task R1-2 (MINOR-5): build the Spark job jar on the final Phase 06 tree and re-check scala-library absence.
FILES CREATED: none (scratch listing /tmp/jl.txt only)
FILES MODIFIED: none (no build file change was needed)
COMMANDS RUN:
- `date` gave `Mon Nov 23 03:12:05 PM CST 2026`.
- `sbt sparkAnalytics/assembly`, foreground, once. Output tail:
  [info] welcome to sbt 1.13.1 (Amazon.com Inc. Java 17.0.21.1)
  [info] 1 file(s) merged using strategy 'Rename' ...
  [info] 11 file(s) merged using strategy 'Discard' ...
  [info] Built: /home/ajcreator/scholarchain/target/jobs/sparkAnalytics-0.1.0-SNAPSHOT.jar
  [info] Jar hash: df1dd1436112d8fe56270804d34f3d62a0e46d44
  [success] Total time: 3 s, completed Nov 23, 2026, 3:12:16 PM
- `ls -l --time-style=full-iso` on the jar gave `-rw-r--r-- 1 ajcreator ajcreator 4071345 2026-11-23 15:12:16.774584118 -0600 target/jobs/sparkAnalytics-0.1.0-SNAPSHOT.jar`.
- `unzip -l` on the jar, then grep counts on the listing (below).
- `unzip -p` on the jar for `META-INF/MANIFEST.MF`.
TEST RESULTS: no tests run. `sbt check` was deliberately not run, as ordered.
VALIDATION RESULTS:
- The jar was rebuilt, not "up to date". Its timestamp is 15:12:16, after 15:08 and not the 07:28 build.
- Jar path: /home/ajcreator/scholarchain/target/jobs/sparkAnalytics-0.1.0-SNAPSHOT.jar
- Size: 4,071,345 bytes. The jar has 1811 entries and 9,885,943 bytes uncompressed.
- Main class: the manifest declares `Main-Class: scholarchain.spark.SparkPipeline`.
- The job's own classes are in the jar. `scholarchain/spark/` has 98 entries, including `Canonical$.class`, `Canonical.class`, `Canonical.tasty`, `Export$.class` and `Export$Table$.class`. The `SparkPipeline` classes did not show up in my truncated `head` output. The manifest names `SparkPipeline` as main, and the jar was built from the final tree, but I did not list it separately.
- scala-library absence check, entry counts in the jar listing:
  - `scala/collection/`: 0
  - `ScalaRunTime`: 0
  - `scala/Predef`: 0
  - `dotty/`: 0
- scala3-library:
  - A grep for the literal name "scala3-library" returns 0, but jar entries don't carry artifact names, so that count means nothing.
  - The bundled Scala 3 runtime is present. 19 entries match `scala/runtime/LazyVals|scala/Tuple|scala/CanEqual`, and the jar has 677 `scala/` entries in total.
  - So scala3-library is bundled and scala-library 2.13 is excluded, as the earlier note in STATUS.md says.
- uPickle:
  - `upickle/`: 377 entries
  - `ujson/`: 108 entries
ASSUMPTIONS:
- The jar manifest's `Main-Class` comes from the existing build.sbt assembly settings, which I did not change.
RISKS:
- Spark jobs on EMR need a Scala 2.13 library supplied by the runtime, because scala-library is excluded. That risk is unchanged and was already recorded in the step 01 note.
- The assembly ran in 3 s because compilation was already current on the final tree.
- I did not list the `SparkPipeline` class entries separately (see VALIDATION RESULTS).
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: orchestrator folds MINOR-5 into STATUS.md through documentation-engineer, then the remaining repair tasks and re-review.
