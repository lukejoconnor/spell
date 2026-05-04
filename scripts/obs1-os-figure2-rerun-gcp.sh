#!/usr/bin/env bash
set -euo pipefail

cd "$(git -C "$(dirname "$0")/.." rev-parse --show-toplevel)"

# Figure 2 32-item rerun for all three open-source models on `paper` HEAD,
# which now includes the SSE stream timeout fixes (793f2e5, dae98a2) and
# GLM-5.1 framework fixes (eb6b0f9, ccabba9) merged after the 2026-05-02
# streamfix runs whose numbers populate the current obs1_data.json.
#
# Layout: 1 shard per (model, benchmark) pair = 6 VMs total.
#   3 models * 2 benchmarks = 6 VMs
#   Each VM runs the full 32-item subset with parallel=4 / n-concurrent=4.
#
# Per Appendix B.1: GLM-5.1 and Qwen3.6 Plus run with `reasoning_effort=high`
# (Qwen's deployment maps this to a 32k thinking-budget). Kimi-K2.6 runs
# with no reasoning parameter because the deployment does not expose one.
# Per Appendix B.2: SBL Figure 2 32-item runs use --paper-compliant.

TB_FLAGS=" --items blind-maze-explorer-5x5 --items blind-maze-explorer-algorithm --items blind-maze-explorer-algorithm.easy --items blind-maze-explorer-algorithm.hard --items build-linux-kernel-qemu --items configure-git-webserver --items count-dataset-tokens --items crack-7z-hash --items crack-7z-hash.easy --items create-bucket --items download-youtube --items eval-mteb --items extract-safely --items fibonacci-server --items gpt2-codegolf --items grid-pattern-transform --items intrusion-detection --items nginx-request-logging --items openssl-selfsigned-cert --items path-tracing-reverse --items play-zork --items polyglot-rust-c --items processing-pipeline --items pytorch-model-cli.easy --items qemu-startup --items run-pdp11-code --items sanitize-git-repo --items sqlite-db-truncate --items swe-bench-astropy-1 --items swe-bench-astropy-2 --items swe-bench-langcodes --items train-fasttext"

SBL_ITEMS='astropy__astropy-12907,astropy__astropy-14182,astropy__astropy-14365,astropy__astropy-14995,astropy__astropy-6938,astropy__astropy-7746,django__django-10914,django__django-10924,django__django-11001,django__django-11019,django__django-11039,django__django-11049,django__django-11099,django__django-11133,django__django-11179,django__django-11283,django__django-11422,django__django-11564,django__django-11583,django__django-11620,django__django-11630,django__django-11742,django__django-11797,django__django-11815,django__django-11848,django__django-11905,django__django-11910,django__django-11964,django__django-11999,django__django-12113,django__django-12125,django__django-12184'

GROUP="${GROUP:-obs1-os-fig2-20260504}"
SREF="${SREF:-paper}"
BREF="${BREF:-paper}"
NETWORK="${SPELL_GCP_NETWORK:-spell-benchmark-vpc}"

launch_tb() {
  local short=$1 model_id=$2 effort_flag=$3
  ./scripts/gcp-benchmark.sh run \
    --name "obs1f2-${short}-tb" \
    --run-group "$GROUP" \
    --spell-ref "$SREF" \
    --benchmarking-ref "$BREF" \
    --network "$NETWORK" \
    --command "uv run python bench.py terminalbench --condition spell --model fireworks-tc:${model_id}${effort_flag} --name obs1f2-${short}-tb --dataset old-core${TB_FLAGS} --n-concurrent 4" 2>&1 | tail -5
}

launch_sbl() {
  local short=$1 model_id=$2 effort_flag=$3
  ./scripts/gcp-benchmark.sh run \
    --name "obs1f2-${short}-sbl" \
    --run-group "$GROUP" \
    --spell-ref "$SREF" \
    --benchmarking-ref "$BREF" \
    --network "$NETWORK" \
    --machine-type e2-standard-16 --disk-size-gb 300 \
    --command "uv run python bench.py swebench --dataset lite --condition spell --model fireworks-tc:${model_id}${effort_flag} --name obs1f2-${short}-sbl --items ${SBL_ITEMS} --parallel 4 --prewarm-envs --paper-compliant" 2>&1 | tail -5
}

EFFORT_HIGH=" --reasoning-effort high"
EFFORT_NONE=""

launch_tb glm51   glm-5p1       "$EFFORT_HIGH" &
P1=$!
launch_tb kimi26  kimi-k2p6     "$EFFORT_NONE" &
P2=$!
launch_tb qwen36p qwen3p6-plus  "$EFFORT_HIGH" &
P3=$!
launch_sbl glm51   glm-5p1       "$EFFORT_HIGH" &
P4=$!
launch_sbl kimi26  kimi-k2p6     "$EFFORT_NONE" &
P5=$!
launch_sbl qwen36p qwen3p6-plus  "$EFFORT_HIGH" &
P6=$!
wait "$P1" "$P2" "$P3" "$P4" "$P5" "$P6"
echo "ALL_DISPATCHES_COMPLETE"
