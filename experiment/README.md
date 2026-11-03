# SGDC-Exp

## Datasets

`datasets.zip` contains the 10 datasets used in the paper: airport, Hospital, food, tax500k, flights, ncvoter, adult, SPStock, CLASSIFICATION and inspection.

```shell
unzip datasets.zip -d ../data
```

## Parameters

All parameters can be passed as `--key=value`; see the option table in the top-level `README.md`. The main ones are `sgdc.alpha` (weight of Succinctness), `sgdc.tau` (threshold), `sgdc.K` (validation batch size) and `sgdc.cov.n` (Coverage sample size). The paper defaults are alpha = 0.6, tau = 0.55, K = 300, n = 10,000.

## Exp-1: Runtime

Hydra, DCFinder and ECP are available in [Metanome](https://github.com/HPI-Information-Systems/Metanome).

## Exp-2: Runtime breakdown

Run SGDC; the stage timings are printed on the console (`RESULT_TIMINGS`) and in the log.

## Exp-3: Scalability

Vary `--rows`, `--sgdc.alpha` and `--sgdc.tau`, and run SGDC.

## Exp-5 and Exp-6: Approximation quality

Run SGDC with different parameters and compare the dumped DCs (`--sgdc.dump`) with the result of exact-score computation.

## Exp-7: Violation detection

Run `java -cp target/SGDC.jar sgdc.dc.inject.CsvErrorInjector <dir> <name> [seed]` (it reads `<dir>/<name>.csv`) to generate an error-injected dataset. The error rate, duplicate probability and mode, and primary-key column are set with `-Dinject.errorRate=...`, `-Dinject.duplicateRowProb=...`, `-Dinject.duplicateMode=...` and `-Dinject.pkColumn=...`.

## Exp-9: Discovery results

The semantic analysis of the discovered DCs is in `exp-8/exp-8.md`.
