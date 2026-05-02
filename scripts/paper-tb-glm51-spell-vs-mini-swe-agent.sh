#!/usr/bin/env bash
# Paper-branch full run: Spell vs mini-swe-agent on Terminal-Bench old-core
# (terminal-bench-core==0.1.1, 80 items), GLM-5.1 via Fireworks.
#
# Mirrors the structure of `paper-tb-opus46-spell-vs-cc.sh`: one VM per
# condition, both running the full 80-item dataset with --n-concurrent 4.
#
# Usage:
#   scripts/paper-tb-glm51-spell-vs-mini-swe-agent.sh [run-group-suffix]
#
# Conditions:
#   - Spell:        --condition spell --model fireworks-tc:glm-5p1 --reasoning-effort high
#   - mini-swe-agent: --condition swe_agent --swe-agent-model fireworks_ai/accounts/fireworks/models/glm-5p1
#
# mini-swe-agent does not currently take reasoning-effort wiring; both runs use
# the same Fireworks GLM-5.1 weights so the comparison isolates the harness.
#
# Spell-side cost is reported via the live usage snapshot; mini-side cost is
# estimated from LiteLLM trajectory tokens against `data/pricing.edn` Fireworks
# rates (see `swe-agent: estimate Fireworks GLM cost` patch on paper).
#
# Monitor:
#   ./scripts/gcp-benchmark.sh wait --run-group <group> --finish

set -euo pipefail

SUFFIX="${1:-2026-05-02}"
RUN_GROUP="paper-tb-glm51-${SUFFIX}"
SPELL_REF="paper"
BENCH_REF="paper"
SPELL_MODEL="fireworks-tc:glm-5p1"
MINI_MODEL="fireworks_ai/accounts/fireworks/models/glm-5p1"
DATASET="old-core"
EFFORT="high"
N_CONCURRENT=4
TEST_TIMEOUT_SEC=600
MACHINE_TYPE="${SPELL_GCP_MACHINE_TYPE:-e2-standard-8}"
NETWORK="${SPELL_GCP_NETWORK:-spell-benchmark-vpc}"

cd "$(dirname "$0")/.."

# Spell shard: default trailing expression ('(!extend)) — do not pass --trailing.
SPELL_VM="paper-tb-glm51-spell"
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
  --fireworks-secret FIREWORKS_API_KEY \
  --spell-ref "${SPELL_REF}" \
  --benchmarking-ref "${BENCH_REF}" \
  --command "${SPELL_COMMAND}"

# mini-swe-agent shard: routes through Fireworks via LiteLLM. The benchmarking
# `paper`-branch swe_agent adapter forwards both FIREWORKS_API_KEY and
# FIREWORKS_AI_API_KEY env aliases.
MINI_VM="paper-tb-glm51-swe-agent"
MINI_RUN_NAME="${MINI_VM}"
MINI_COMMAND="uv run python bench.py terminalbench \
  --dataset ${DATASET} \
  --condition swe_agent \
  --swe-agent-model ${MINI_MODEL} \
  --n-concurrent ${N_CONCURRENT} \
  --test-timeout-sec ${TEST_TIMEOUT_SEC} \
  --name ${MINI_RUN_NAME}"

echo "==> Launching ${MINI_VM}"
./scripts/gcp-benchmark.sh run \
  --name "${MINI_VM}" \
  --run-group "${RUN_GROUP}" \
  --machine-type "${MACHINE_TYPE}" \
  --network "${NETWORK}" \
  --fireworks-secret FIREWORKS_API_KEY \
  --spell-ref "${SPELL_REF}" \
  --benchmarking-ref "${BENCH_REF}" \
  --command "${MINI_COMMAND}"

echo
echo "Run group: ${RUN_GROUP}"
echo "Monitor:   ./scripts/gcp-benchmark.sh wait --run-group ${RUN_GROUP} --finish"
