package scholarchain.hadoop

/** Counter names. Every job reports the same core set in group `scholarchain`:
  *
  *   - INPUT_RECORDS: units a mapper read (DBLP records, JSON lines)
  *   - ACCEPTED_RECORDS: units that passed into the stage's output
  *   - REJECTED_RECORDS: units written to the reject output (every reject increments it)
  *   - SKIPPED_RECORDS: valid units outside the stage's scope (DBLP www, proceedings, data)
  *   - SCHEMA_FAILURE: rejects caused by contract validation (a subset of REJECTED_RECORDS)
  *   - DUPLICATE_RECORDS / CONFLICT_RECORDS: extra copies of one key, and copies that differ
  *   - OVERSIZE_BLOCKS / OVERSIZE_TEAMS: blocks and teams above their caps; a researcher block
  *     counts once, whether or not its full-name parts fit under the cap (fallback blocks count in
  *     FALLBACK_BLOCKS_REFUSED instead)
  *   - EMITTED_PAIRS: coauthor pair candidates written
  *   - MISSING_KEY: a unit that lacks the key a stage needs (DBLP key, DOI, name key)
  *
  * In a mapper, INPUT_RECORDS = ACCEPTED_RECORDS + REJECTED_RECORDS + SKIPPED_RECORDS.
  */
object Ctr:
  val Group = "scholarchain"
  val Input = "INPUT_RECORDS"
  val Accepted = "ACCEPTED_RECORDS"
  val Rejected = "REJECTED_RECORDS"
  val Skipped = "SKIPPED_RECORDS"
  val SchemaFailure = "SCHEMA_FAILURE"
  val Duplicate = "DUPLICATE_RECORDS"
  val Conflict = "CONFLICT_RECORDS"
  val OversizeBlock = "OVERSIZE_BLOCKS"
  val OversizeTeam = "OVERSIZE_TEAMS"
  val EmittedPair = "EMITTED_PAIRS"
  val MissingKey = "MISSING_KEY"
  val InvalidIdentifier = "INVALID_IDENTIFIER"
  val PairsRefused = "PAIRS_REFUSED_BY_CAP"
  val PairsBeforeCap = "CANDIDATE_PAIRS_BEFORE_CAP"
  val WorksWithoutAuthors = "WORKS_WITHOUT_AUTHORS"
  val FallbackCompared = "FALLBACK_BLOCKS_COMPARED"
  val FallbackRefused = "FALLBACK_BLOCKS_REFUSED"
  val ExactCandidates = "EXACT_CANDIDATES"
  val BlockedCandidates = "BLOCKED_CANDIDATES"
