#!/usr/bin/env bash
# Paper-branch full run: Spell vs Claude Code on Terminal-Bench 1.1, Opus 4.6 / medium.
#
# Usage:
#   scripts/paper-tb-opus46-spell-vs-cc.sh [run-group-suffix]
#
# Dispatches 2 GCP VMs (one shard for Spell, one shard for Claude Code) running
# the full Terminal-Bench 1.1 old-core dataset (80 items). Items run in parallel
# inside each VM via --n-concurrent 4. Knobs (n-concurrent, test-timeout-sec,
# machine type, network, refs) match the 2026-04-29 paper-fig3 GPT-5.4 TB
# replication run for apples-to-apples behavior. Promoted from the pilot script
# `paper-pilot-tb-opus46-spell-vs-cc.sh` after a 4-item dispatch validated the
# script and the CC OAUTH/cost-accounting paths (notebook entry
# `2026-04-30-paper-tb11-opus46-pilot`).
#
# Claude Code uses the Max-subscription OAUTH token. The benchmarking
# `paper`-branch CC adapter (`docker_agents.py`) raises if
# CLAUDE_CODE_OAUTH_TOKEN is not set, and the GCP VM startup script
# (`scripts/gcp-startup.sh`) populates it from the
# `CLAUDE_CODE_OAUTH_TOKEN` secret in Secret Manager. Cost on CC timeout
# rows is recovered from per-message stream usage by the post-pilot fix
# in benchmarking commit 0b074fa (PR #68).
#
# Monitor:
#   ./scripts/gcp-benchmark.sh wait --run-group <group> --finish

set -euo pipefail

SUFFIX="${1:-2026-04-30}"
RUN_GROUP="paper-tb-opus46-${SUFFIX}"
SPELL_REF="paper"
BENCH_REF="paper"
SPELL_MODEL="anthropic-tc:claude-opus-4-6"
CC_MODEL="claude-opus-4-6"
DATASET="old-core"
EFFORT="medium"
N_CONCURRENT=4
TEST_TIMEOUT_SEC=600
MACHINE_TYPE="${SPELL_GCP_MACHINE_TYPE:-e2-standard-8}"
NETWORK="${SPELL_GCP_NETWORK:-spell-benchmark-vpc}"

cd "$(dirname "$0")/.."

# Spell shard: default trailing expression ('(!extend)) — do not pass --trailing.
SPELL_VM="paper-tb-opus46-spell"
SPELL_RUN_NAME="${SPELL_VM}"
SPELL_COMMAND="uv run python bench.py terminalbench \
  --dataset ${DATASET} \
  --condition spell \
  --model ${SPELL_MODEL} \
  --reasoning-effort ${EFFORT} \
  --n-concurrent ${N_CONCURRENT} \
  --test-timeout-sec ${TEST_TIMEOUT_SEC} \
  --name ${SPELL_RUN_NAME}"

echo "==> Launching ${SPELL_VM}"
./scripts/gcp-benchmark.sh run \
  --name "${SPELL_VM}" \
  --run-group "${RUN_GROUP}" \
  --machine-type "${MACHINE_TYPE}" \
  --network "${NETWORK}" \
  --spell-ref "${SPELL_REF}" \
  --benchmarking-ref "${BENCH_REF}" \
  --command "${SPELL_COMMAND}"

# Claude Code shard: routes through OAUTH token populated by gcp-startup.sh
# from Secret Manager `CLAUDE_CODE_OAUTH_TOKEN`.
CC_VM="paper-tb-opus46-cc"
CC_RUN_NAME="${CC_VM}"
CC_COMMAND="uv run python bench.py terminalbench \
  --dataset ${DATASET} \
  --condition claude_code \
  --claude-model ${CC_MODEL} \
  --reasoning-effort ${EFFORT} \
  --n-concurrent ${N_CONCURRENT} \
  --test-timeout-sec ${TEST_TIMEOUT_SEC} \
  --name ${CC_RUN_NAME}"

echo "==> Launching ${CC_VM}"
./scripts/gcp-benchmark.sh run \
  --name "${CC_VM}" \
  --run-group "${RUN_GROUP}" \
  --machine-type "${MACHINE_TYPE}" \
  --network "${NETWORK}" \
  --spell-ref "${SPELL_REF}" \
  --benchmarking-ref "${BENCH_REF}" \
  --command "${CC_COMMAND}"

echo
echo "Run group: ${RUN_GROUP}"
echo "Monitor:   ./scripts/gcp-benchmark.sh wait --run-group ${RUN_GROUP} --finish"
