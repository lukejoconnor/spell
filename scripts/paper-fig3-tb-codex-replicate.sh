#!/usr/bin/env bash
# Replicate Figure 3 Terminal-Bench 1.1 Codex CLI rows from paper_v7.
#
# Usage:
#   scripts/paper-fig3-tb-codex-replicate.sh [run-group-suffix]
#
# Launches 3 GCP VMs (one per reasoning effort) to run Codex CLI on the full
# Terminal-Bench 1.1 old-core dataset (80 items) with --n-concurrent 4 and a
# 10-minute test timeout. All VMs use:
#   - Spell paper branch (carries the gcp-benchmark.sh launcher)
#   - benchmarking paper branch
#   - codex-model gpt-5.4
#
# Differences vs. paper-fig3-tb-replicate.sh (Spell variant):
#   - --condition codex     (was: spell)
#   - --codex-model gpt-5.4 (was: --model openai-tc:gpt-5.4)
#   - VM/run names use "codex" instead of "spell"
# All other flags (dataset, n-concurrent, test-timeout-sec, machine type,
# network, refs) match the Spell script for apples-to-apples comparison.
#
# After dispatch, monitor with:
#   ./scripts/gcp-benchmark.sh wait --run-group <group> --finish

set -euo pipefail

SUFFIX="${1:-2026-04-30}"
RUN_GROUP="paper-fig3-tb-codex-${SUFFIX}"
SPELL_REF="paper"
BENCH_REF="paper"
CODEX_MODEL="gpt-5.4"
DATASET="old-core"
N_CONCURRENT=4
TEST_TIMEOUT_SEC=600
MACHINE_TYPE="${SPELL_GCP_MACHINE_TYPE:-e2-standard-8}"
NETWORK="${SPELL_GCP_NETWORK:-spell-benchmark-vpc}"

cd "$(dirname "$0")/.."

for EFFORT in low medium high; do
  VM_NAME="paper-fig3-tb-codex-gpt54-${EFFORT}"
  RUN_NAME="paper-fig3-tb-codex-gpt54-${EFFORT}"
  COMMAND="uv run python bench.py terminalbench \
    --dataset ${DATASET} \
    --condition codex \
    --codex-model ${CODEX_MODEL} \
    --reasoning-effort ${EFFORT} \
    --n-concurrent ${N_CONCURRENT} \
    --test-timeout-sec ${TEST_TIMEOUT_SEC} \
    --name ${RUN_NAME}"

  echo "==> Launching ${VM_NAME}"
  ./scripts/gcp-benchmark.sh run \
    --name "${VM_NAME}" \
    --run-group "${RUN_GROUP}" \
    --machine-type "${MACHINE_TYPE}" \
    --network "${NETWORK}" \
    --spell-ref "${SPELL_REF}" \
    --benchmarking-ref "${BENCH_REF}" \
    --command "${COMMAND}"
done

echo
echo "Run group: ${RUN_GROUP}"
echo "Monitor:   ./scripts/gcp-benchmark.sh wait --run-group ${RUN_GROUP} --finish"
