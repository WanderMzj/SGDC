# SGDC

SGDC is a solution to **score-guided denial constraint (DC) discovery**.
Given a relational dataset, SGDC efficiently discovers high-quality DCs whose scores exceed a user-specified threshold, instead of enumerating all minimal valid DCs.

The score of a DC is

    Score(phi) = alpha * Succ(phi) + (1 - alpha) * Coverage(phi)

where `alpha` is the weight of Succinctness and `1 - alpha` the weight of Coverage.

SGDC integrates three components in an iterative framework:

- sample-based discovery,
- score-driven selective validation,
- sample augmentation using DC-violating tuple pairs.

To improve efficiency, SGDC estimates Coverage by rejection sampling, with a provable high-probability error bound, and prunes the search with an admissible score upper bound.

## Requirements

- JDK 17 or later
- Maven 3.6 or later (only needed for the Maven build)

## Build

```shell
mvn -DskipTests clean package
```

This produces the self-contained executable `target/SGDC.jar`.

If you cannot use Maven's shade plugin, compile with `javac` instead (dependencies are the jars listed in `pom.xml`):

```shell
javac -encoding UTF-8 -d build -cp "<dependency jars>" $(find src/main/java -name "*.java")
```

The few classes used from Metanome (`de.metanome.algorithm_integration`) are vendored in `src/main/java`, so no Metanome artifact is required. See `NOTICE`.

## Datasets

The 10 datasets used in the paper are in `experiment/datasets.zip`:

```shell
unzip experiment/datasets.zip -d data
```

## Run

```shell
java -jar target/SGDC.jar data/airport.csv
```

With options:

```shell
java -jar target/SGDC.jar --dataset=data/airport.csv --sgdc.alpha=0.6 --sgdc.tau=0.55 --sgdc.seed=1 --sgdc.dump=out.tsv
```

For larger datasets give the JVM more heap, e.g. `java -Xmx16g -jar target/SGDC.jar data/flights.csv`.

Or use the helper scripts, which unzip the data, build, and run `airport`:

```shell
./run.sh          # Linux / macOS
run.bat           # Windows
```

Options can be given as `--key=value` on the command line or as JVM properties `-Dkey=value`; the command line wins. Keys starting with `fs.`, `rs.`, `sgdc.`, `input.` or `inject.` are passed to the corresponding component as system properties; any other unknown `--key` is rejected.

## Options

| Key | Default | Meaning |
|---|---|---|
| dataset | none | CSV path (may also be the only bare argument) |
| rows | -1 | number of rows to read, -1 = all |
| sgdc.alpha | 0.6 | weight of Succinctness; Coverage has weight 1 - alpha |
| sgdc.tau | 0.55 | score threshold |
| sgdc.K | 300 | size of the batch of candidates validated together |
| sgdc.seed | 1 | global random seed; all sampling components derive from it |
| sgdc.cov.n | 10000 | accepted samples per Coverage estimate |
| sgdc.upper | safe | score upper bound (`safe` is admissible) |
| sgdc.exact | false | if true, re-score the output with exact Coverage and keep only Score >= tau (the search then runs with threshold tau - sgdc.exact.margin) |
| sgdc.exact.margin | 0.01 | threshold relaxation used when sgdc.exact=true |
| sgdc.validateLow | true | also validate low-scoring complete nodes, used only as minimality blockers |
| sgdc.verify.threads | CPU count - 1 | validation threads (results do not depend on it) |
| sgdc.verify.timeoutMin | 120 | validation time budget in minutes |
| sgdc.dump | none | write the output DCs to this TSV file |
| noCrossColumn | true | do not generate cross-column predicates |
| minimumSharedValue | 0.15 | shared-value ratio required for cross-column predicates |
| fs.* / rs.* | see `RunConfig` | parameters of focused / random sampling |
| input.charset / input.delimiter / input.padShort | UTF-8 / , / false | CSV reading |

The dump file is a TSV with columns `size, succ, coverage, score, dc`, sorted by DC string. With the same input and seed it is byte-identical across runs.

## Output and exit codes

| Standard output | Meaning |
|---|---|
| `RESULT_DISCOVERY_MS t` | total discovery time in ms |
| `RESULT_P n` | size of the predicate space |
| `RESULT_DCS n` | number of output DCs |
| `RESULT_TIMINGS ...` | per-stage times |
| `RESULT_COV_CAPPED c minAccepted=k` | number of Coverage estimates that hit the sample cap |
| `RESULT_INCOMPLETE b` | `true` if the validation budget was exhausted and the result is partial |

| Exit code | Meaning |
|---|---|
| 0 | finished normally |
| 2 | invalid arguments |
| 3 | validation timed out; the result is partial |

## Experiments

See `experiment/README.md`. The semantic annotation of the discovered DCs is in `experiment/exp-8/exp-8.md`.
