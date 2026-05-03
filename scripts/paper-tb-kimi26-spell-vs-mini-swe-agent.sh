#!/usr/bin/env bash
# Paper-branch full run: Spell vs mini-swe-agent on Terminal-Bench old-core
# (terminal-bench-core==0.1.1, 80 items), Kimi-K2.6 via Fireworks.
#
# Mirrors `paper-tb-glm51-spell-vs-mini-swe-agent.sh`: one VM per condition,
# both running the full 80-item dataset with --n-concurrent 4. Same effort
# config (`high`) as the Observation 1 / Figure 1 open-model analysis on the
# 32-item subset (`obs1tc2-kimi26-tb-*`).
#
# Usage:
#   scripts/paper-tb-kimi26-spell-vs-mini-swe-agent.sh [run-group-suffix]
#
# Conditions:
#   - Spell:        --condition spell --model fireworks-tc:kimi-k2p6 --reasoning-effort high
#   - mini-swe-agent: --condition swe_agent --swe-agent-model fireworks_ai/accounts/fireworks/models/kimi-k2p6
#
# mini-swe-agent does not currently take reasoning-effort wiring; both runs use
# the same Fireworks Kimi-K2.6 weights so the comparison isolates the harness.
#
# Spell-side cost is reported via the live usage snapshot; mini-side cost is
# estimated from LiteLLM trajectory tokens against `data/pricing.edn` Fireworks
# rates.
#
# Monitor:
#   ./scripts/gcp-benchmark.sh wait --run-group <group> --finish

set -euo pipefail

SUFFIX="${1:-2026-05-03}"
RUN_GROUP="paper-tb-kimi26-${SUFFIX}"
SPELL_REF="paper"
BENCH_REF="paper"
SPELL_MODEL="fireworks-tc:kimi-k2p6"
MINI_MODEL="fireworks_ai/accounts/fireworks/models/kimi-k2p6"
DATASET="old-core"
EFFORT="high"
N_CONCURRENT=4
TEST_TIMEOUT_SEC=600
MACHINE_TYPE="${SPELL_GCP_MACHINE_TYPE:-e2-standard-8}"
NETWORK="${SPELL_GCP_NETWORK:-spell-benchmark-vpc}"

cd "$(dirname "$0")/.."

# Spell shard: default trailing expression ('(!extend)) — do not pass --trailing.
SPELL_VM="paper-tb-kimi26-spell"
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
MINI_VM="paper-tb-kimi26-swe-agent"
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
