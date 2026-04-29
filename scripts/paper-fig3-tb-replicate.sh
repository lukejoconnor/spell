#!/usr/bin/env bash
# Replicate Figure 3 Terminal-Bench 1.1 Spell rows from paper_v7.
#
# Usage:
#   scripts/paper-fig3-tb-replicate.sh [run-group-suffix]
#
# Launches 3 GCP VMs (one per reasoning effort) to run Spell on the full
# Terminal-Bench 1.1 old-core dataset (80 items) with --n-concurrent 4 and a
# 10-minute test timeout. All VMs use:
#   - Spell paper branch
#   - benchmarking paper branch
#   - openai-tc:gpt-5.4
#
# After dispatch, monitor with:
#   ./scripts/gcp-benchmark.sh wait --run-group <group> --finish

set -euo pipefail

SUFFIX="${1:-2026-04-29}"
RUN_GROUP="paper-fig3-tb-${SUFFIX}"
SPELL_REF="paper"
BENCH_REF="paper"
MODEL="openai-tc:gpt-5.4"
DATASET="old-core"
N_CONCURRENT=4
TEST_TIMEOUT_SEC=600
MACHINE_TYPE="${SPELL_GCP_MACHINE_TYPE:-e2-standard-8}"
NETWORK="${SPELL_GCP_NETWORK:-spell-benchmark-vpc}"

cd "$(dirname "$0")/.."

for EFFORT in low medium high; do
  VM_NAME="paper-fig3-tb-spell-gpt54-${EFFORT}"
  RUN_NAME="paper-fig3-tb-spell-gpt54-${EFFORT}"
  COMMAND="uv run python bench.py terminalbench \
    --dataset ${DATASET} \
    --condition spell \
    --model ${MODEL} \
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
