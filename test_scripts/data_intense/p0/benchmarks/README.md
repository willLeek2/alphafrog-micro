# P0 data benchmark, fixture, and consistency harness

This directory is a black-box T5 artifact. It does not edit or replace T1-T4
module tests. The scripts import the production Python loader and resource
collector from the current checkout.

## Coverage

- deterministic 150,000-row and 500,000-row CSV fixtures;
- exactly 100 small CSV files (1,000 rows each);
- typed, `usecols`, complete-row chunk, and multi-file loader cases;
- loader JSONL `logicalBytesScanned` / `datasetOpenCount` validation;
- deterministic production `SandboxResourceUsageCollector` output;
- STANDARD/HEAVY memory, capacity-unit, and collector identity contract probe;
- terminal resource usage ↔ full `data_analysis_observability` consistency;
- JSON per case, an aggregate CSV, and a JSON suite report.

Generated CSV fixtures are temporary and must not be committed. Machine-readable
result files are small and may be retained in the T5 Owner artifact set.

## Full run

From the repository root:

```bash
uv run --with pandas --with pydantic \
  python test_scripts/data_intense/p0/benchmarks/run_suite.py \
  --preset full \
  --work-dir /tmp/alphafrog-data-intense-p0 \
  --result-dir test_scripts/data_intense/p0/benchmarks/results/local-full
```

The full preset creates 102 datasets and 750,000 total rows. The small-file
case opens exactly 100 files. Timings and peak RSS are evidence for the machine
that executed the run, not universal performance thresholds.
The resource-class probe is deliberately labeled as configuration/collector
identity evidence; it does not claim live Docker throughput or memory-pressure
performance.

## Fast validation

```bash
uv run --with pandas --with pydantic \
  python -m unittest \
  test_scripts/data_intense/p0/benchmarks/test_benchmark_tools.py

uv run --with pandas --with pydantic \
  python test_scripts/data_intense/p0/benchmarks/run_suite.py \
  --preset smoke \
  --work-dir /tmp/alphafrog-data-intense-smoke \
  --result-dir /tmp/alphafrog-data-intense-smoke-results
```

Every command exits non-zero on an oracle mismatch. The consistency checker
requires a full observability response with `calls`; a status-only summary is
not accepted as proof.
