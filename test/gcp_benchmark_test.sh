#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." >/dev/null 2>&1 && pwd)"

source "$REPO_ROOT/scripts/gcp-benchmark.sh"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_eq() {
  local expected="$1"
  local actual="$2"
  local message="$3"
  if [[ "$expected" != "$actual" ]]; then
    fail "${message}: expected [$expected], got [$actual]"
  fi
}

assert_contains() {
  local haystack="$1"
  local needle="$2"
  local message="$3"
  if [[ "$haystack" != *"$needle"* ]]; then
    fail "${message}: missing [$needle]"
  fi
}

assert_file_exists() {
  local path="$1"
  local message="$2"
  [[ -f "$path" ]] || fail "${message}: missing file [$path]"
}

assert_not_exists() {
  local path="$1"
  local message="$2"
  [[ ! -e "$path" ]] || fail "${message}: unexpected path [$path]"
}

test_extract_tar_stream_flattens_paths() {
  local tmpdir
  tmpdir="$(mktemp -d)"
  mkdir -p "$tmpdir/remote/results/subdir"
  printf 'alpha\n' >"$tmpdir/remote/results/result.json"
  printf 'beta\n' >"$tmpdir/remote/results/subdir/detail.log"

  tar -cf - -C "$tmpdir/remote/results" . | extract_tar_stream_into_dir "$tmpdir/local"

  assert_file_exists "$tmpdir/local/result.json" "pull should flatten top-level contents"
  assert_file_exists "$tmpdir/local/subdir/detail.log" "pull should keep nested contents"
  assert_not_exists "$tmpdir/local/results" "pull should not create an extra results directory"
  rm -rf "$tmpdir"
}

test_render_run_wrapper_contains_status_transitions() {
  RUN_GROUP="batch one"
  RUN_COMMAND='uv run python bench.py swebench --dataset lite'
  SPELL_REF="feature/spell"
  BENCHMARKING_REF="feature/benchmarking"

  local wrapper
  wrapper="$(render_run_wrapper_script)"

  assert_contains "$wrapper" "write_status starting" "wrapper should mark starting"
  assert_contains "$wrapper" "write_status running" "wrapper should mark running"
  assert_contains "$wrapper" "write_status finished" "wrapper should mark finished"
  assert_contains "$wrapper" "write_status failed" "wrapper should mark failed"
  assert_contains "$wrapper" "BENCHMARK_COMMAND=" "wrapper should capture the command"
}

test_filter_instances_json_by_run_group() {
  OPERATE_ALL=0
  local json
  json='[
    {
      "name": "vm-a",
      "labels": {"run-group": "group-a"},
      "metadata": {"items": [{"key": "run-group", "value": "batch-a"}]}
    },
    {
      "name": "vm-b",
      "labels": {"run-group": "group-b"},
      "metadata": {"items": [{"key": "run-group", "value": "batch-b"}]}
    },
    {
      "name": "vm-c",
      "labels": {"run-group": "group-a"},
      "metadata": {"items": [{"key": "run-group", "value": "batch-a"}]}
    }
  ]'

  local filtered
  filtered="$(printf '%s' "$json" | filter_instances_json_by_run_group "group-a" "batch-a")"

  local names
  names="$(printf '%s' "$filtered" | python3 -c 'import json,sys; print(",".join(item["name"] for item in json.load(sys.stdin)))')"
  assert_eq "vm-a,vm-c" "$names" "status-all filtering should match the requested run group"
}

test_finish_calls_pull_before_stop() {
  local calls=""
  pull_results_from_instance() { calls="${calls}pull:$1 "; }
  stop_instance_named() { calls="${calls}stop:$1 "; }

  finish_instance_named "vm-one" "us-central1-a"

  assert_eq "pull:vm-one stop:vm-one " "$calls" "finish should pull before delete"
}

test_finish_all_skips_active_vms() {
  list_matching_instances() {
    printf 'done-vm\tus-central1-a\tRUNNING\trg\tmain\tmain\n'
    printf 'running-vm\tus-central1-a\tRUNNING\trg\tmain\tmain\n'
    printf 'failed-vm\tus-central1-a\tRUNNING\trg\tmain\tmain\n'
  }
  read_benchmark_state() {
    case "$1" in
      done-vm) printf 'finished\n' ;;
      failed-vm) printf 'failed\n' ;;
      *) printf 'running\n' ;;
    esac
  }

  local calls=""
  finish_instance_named() { calls="${calls}$1 "; }

  local output_file
  output_file="$(mktemp)"
  finish_all_instances >"$output_file"
  local output
  output="$(cat "$output_file")"
  rm -f "$output_file"

  assert_eq "done-vm failed-vm " "$calls" "finish-all should only stop terminal VMs"
  assert_contains "$output" "skipped active VMs: running-vm:running" "finish-all should summarize skipped active VMs"
}

test_wait_for_completion_summarizes_and_finishes() {
  RUN_GROUP="batch-a"
  WAIT_INTERVAL_SECONDS=1
  WAIT_TIMEOUT_SECONDS=5
  WAIT_AND_FINISH=1
  SECONDS=0

  local finish_calls=0
  local phase_file
  phase_file="$(mktemp)"
  printf 'running\n' >"$phase_file"
  list_matching_instances() {
    printf 'vm-a\tus-central1-a\tRUNNING\tbatch-a\tmain\tmain\n'
    printf 'vm-b\tus-central1-a\tRUNNING\tbatch-a\tmain\tmain\n'
  }
  read_benchmark_state() {
    if [[ "$(cat "$phase_file")" == "running" ]]; then
      printf 'running\n'
    else
      case "$1" in
        vm-a) printf 'finished\n' ;;
        *) printf 'failed\n' ;;
      esac
    fi
  }
  finish_all_instances() {
    finish_calls=$((finish_calls + 1))
  }
  resolve_project() { PROJECT="spellbenchmarking"; }
  require_cmd() { :; }
  sleep() {
    printf 'terminal\n' >"$phase_file"
    SECONDS=$((SECONDS + ${1:-0}))
  }

  local output_file
  output_file="$(mktemp)"
  wait_for_completion >"$output_file"
  local output
  output="$(cat "$output_file")"
  rm -f "$output_file"
  rm -f "$phase_file"

  assert_contains "$output" "0/2 terminal, 0 finished, 0 failed, 2 running" "wait should report active runs before completion"
  assert_contains "$output" "2/2 terminal, 1 finished, 1 failed, 0 running" "wait should report terminal runs before exiting"
  assert_eq "1" "$finish_calls" "wait --finish should trigger finish-all once"
}

test_wait_for_completion_times_out() {
  RUN_GROUP="batch-b"
  WAIT_INTERVAL_SECONDS=1
  WAIT_TIMEOUT_SECONDS=2
  WAIT_AND_FINISH=0
  SECONDS=0

  list_matching_instances() {
    printf 'vm-a\tus-central1-a\tRUNNING\tbatch-b\tmain\tmain\n'
  }
  read_benchmark_state() {
    printf 'running\n'
  }
  resolve_project() { PROJECT="spellbenchmarking"; }
  require_cmd() { :; }
  sleep() { SECONDS=$((SECONDS + ${1:-0})); }

  if (wait_for_completion >/dev/null 2>&1); then
    fail "wait should fail on timeout"
  fi
}

test_help_and_argument_parsing() {
  bash "$REPO_ROOT/scripts/gcp-benchmark.sh" --help >/dev/null
  bash "$REPO_ROOT/scripts/gcp-benchmark.sh" start --help >/dev/null
  bash "$REPO_ROOT/scripts/gcp-benchmark.sh" pull-all --help >/dev/null
  bash "$REPO_ROOT/scripts/gcp-benchmark.sh" wait --help >/dev/null

  if bash "$REPO_ROOT/scripts/gcp-benchmark.sh" finish-all >/dev/null 2>&1; then
    fail "finish-all should require --run-group or --all"
  fi

  if bash "$REPO_ROOT/scripts/gcp-benchmark.sh" wait >/dev/null 2>&1; then
    fail "wait should require --run-group or --all"
  fi
}

main() {
  test_extract_tar_stream_flattens_paths
  test_render_run_wrapper_contains_status_transitions
  test_filter_instances_json_by_run_group
  test_finish_calls_pull_before_stop
  test_finish_all_skips_active_vms
  test_wait_for_completion_summarizes_and_finishes
  test_wait_for_completion_times_out
  test_help_and_argument_parsing
  printf 'PASS: gcp benchmark launcher tests\n'
}

main "$@"
