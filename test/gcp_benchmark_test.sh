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

assert_occurrences() {
  local haystack="$1"
  local needle="$2"
  local expected="$3"
  local message="$4"
  local actual
  actual="$(HAYSTACK="$haystack" NEEDLE="$needle" python3 - <<'PY'
import os

print(os.environ["HAYSTACK"].count(os.environ["NEEDLE"]))
PY
)"
  if [[ "$actual" != "$expected" ]]; then
    fail "${message}: expected [$expected], got [$actual]"
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
  assert_contains "$wrapper" 'sync_repo_ref "$HOME/spell" "$SPELL_REF"' "wrapper should sync the spell repo before running"
  assert_contains "$wrapper" 'sync_repo_ref "$HOME/spell/benchmarking" "$BENCHMARKING_REF"' "wrapper should sync the benchmarking repo before running"
  assert_contains "$wrapper" 'git -C "$repo_dir" "${git_args[@]}" fetch origin "$ref" --depth 1' "wrapper should fetch the requested ref on warm VMs"
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

test_dispatch_instance_reuses_existing_vm() {
  INSTANCE_NAME="warm-vm"
  ZONE="us-central1-a"
  RUN_GROUP="batch-warm"
  RUN_COMMAND='uv run python bench.py swebench --dataset lite'

  local calls=""
  local state_file
  state_file="$(mktemp)"
  require_cmd() { :; }
  resolve_project() { PROJECT="spellbenchmarking"; }
  describe_instance_json() { printf '{}'; }
  instance_summary_row_from_json() { printf 'us-central1-a\tRUNNING\tbatch-warm\tmain\tmain\told-command\n'; }
  wait_for_startup() { calls="${calls}wait:$1:$2 "; }
  read_benchmark_state() { printf '%s:%s:%s\n' "$1" "$2" "$3" >"$state_file"; printf 'finished\n'; }
  refresh_instance_metadata_for_run() { calls="${calls}meta:$1:$2 "; }
  launch_benchmark_command() { calls="${calls}launch:$1:$2 "; }

  dispatch_instance

  assert_eq \
    "wait:warm-vm:us-central1-a meta:warm-vm:us-central1-a launch:warm-vm:us-central1-a " \
    "$calls" \
    "dispatch should wait, refresh metadata, and launch on the existing VM"
  assert_eq \
    "warm-vm:us-central1-a:RUNNING" \
    "$(cat "$state_file")" \
    "dispatch should check the benchmark state before relaunching"
  rm -f "$state_file"
}

test_dispatch_instance_refuses_active_benchmark() {
  INSTANCE_NAME="busy-vm"
  ZONE="us-central1-a"
  RUN_GROUP="batch-busy"
  RUN_COMMAND='uv run python bench.py swebench --dataset lite'

  require_cmd() { :; }
  resolve_project() { PROJECT="spellbenchmarking"; }
  describe_instance_json() { printf '{}'; }
  instance_summary_row_from_json() { printf 'us-central1-a\tRUNNING\tbatch-busy\tmain\tmain\told-command\n'; }
  wait_for_startup() { :; }
  read_benchmark_state() { printf 'running\n'; }
  refresh_instance_metadata_for_run() { fail "dispatch should not refresh metadata for an active benchmark"; }
  launch_benchmark_command() { fail "dispatch should not launch over an active benchmark"; }

  if (dispatch_instance >/dev/null 2>&1); then
    fail "dispatch should fail when the VM already has a running benchmark"
  fi
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
  local help_output
  help_output="$(bash "$REPO_ROOT/scripts/gcp-benchmark.sh" --help)"
  assert_contains "$help_output" "default: 604800 / 7d" "help should document the 7 day wait default"
  assert_contains "$help_output" "Auto-stop window" "help should document that max-run-duration stops rather than deletes VMs"
  bash "$REPO_ROOT/scripts/gcp-benchmark.sh" start --help >/dev/null
  bash "$REPO_ROOT/scripts/gcp-benchmark.sh" dispatch --help >/dev/null
  bash "$REPO_ROOT/scripts/gcp-benchmark.sh" pull-all --help >/dev/null
  bash "$REPO_ROOT/scripts/gcp-benchmark.sh" wait --help >/dev/null

  if bash "$REPO_ROOT/scripts/gcp-benchmark.sh" finish-all >/dev/null 2>&1; then
    fail "finish-all should require --run-group or --all"
  fi

  if bash "$REPO_ROOT/scripts/gcp-benchmark.sh" wait >/dev/null 2>&1; then
    fail "wait should require --run-group or --all"
  fi

  if bash "$REPO_ROOT/scripts/gcp-benchmark.sh" dispatch >/dev/null 2>&1; then
    fail "dispatch should require --command"
  fi
}

test_launcher_stops_instead_of_deleting_on_timeout() {
  local launcher_script
  launcher_script="$(cat "$REPO_ROOT/scripts/gcp-benchmark.sh")"

  assert_contains "$launcher_script" '--instance-termination-action STOP' "launcher should stop instances at max-run-duration"
}

test_startup_script_preserves_claude_auth_and_bootstrap_marker() {
  local startup_script
  startup_script="$(cat "$REPO_ROOT/scripts/gcp-startup.sh")"

  assert_contains "$startup_script" '>"$USER_HOME/.claude.json"' "startup should write Claude auth where host adapters read it"
  if [[ "$startup_script" == *'.claude/.claude.json'* ]]; then
    fail "startup should not write Claude auth to ~/.claude/.claude.json"
  fi
  assert_contains "$startup_script" 'BOOTSTRAP_MARKER="/var/lib/spell-benchmark/bootstrap-done"' "startup should use a durable bootstrap marker"
  assert_contains "$startup_script" 'running|startup|starting' "startup should treat interrupted starting runs as failed on reboot"
  assert_contains "$startup_script" $'materialize_secrets_and_env\n  systemctl enable --now docker\n  wait_for_docker' "startup fast path should wait for docker before continuing"
  assert_contains "$startup_script" 'ensure_tmux_session' "startup should recreate the tmux session on reboot"
}

test_wait_for_completion_treats_persistent_unreachable_as_terminal() {
  RUN_GROUP="batch-unreachable"
  WAIT_INTERVAL_SECONDS=1
  WAIT_TIMEOUT_SECONDS=5
  WAIT_UNREACHABLE_FAILURES=2
  WAIT_AND_FINISH=0
  SECONDS=0

  list_matching_instances() {
    printf 'vm-a\tus-central1-a\tRUNNING\tbatch-unreachable\tmain\tmain\n'
  }
  read_benchmark_state() {
    printf 'unreachable\n'
  }
  resolve_project() { PROJECT="spellbenchmarking"; }
  require_cmd() { :; }
  sleep() { SECONDS=$((SECONDS + ${1:-0})); }

  local output_file
  output_file="$(mktemp)"
  wait_for_completion >"$output_file"
  local output
  output="$(cat "$output_file")"
  rm -f "$output_file"

  assert_contains "$output" "0/1 terminal, 0 finished, 0 failed, 0 running, 0 startup, 0 unknown, 1 unreachable" "first unreachable poll should stay non-terminal"
  assert_contains "$output" "1/1 terminal, 0 finished, 1 failed, 0 running, 0 startup, 0 unknown, 0 unreachable" "persistent unreachable VM should become terminal"
}

test_wait_for_completion_resets_unreachable_budget_after_success() {
  RUN_GROUP="batch-flaky"
  WAIT_INTERVAL_SECONDS=1
  WAIT_TIMEOUT_SECONDS=6
  WAIT_UNREACHABLE_FAILURES=2
  WAIT_AND_FINISH=0
  SECONDS=0

  local phase_file
  phase_file="$(mktemp)"
  printf '0\n' >"$phase_file"
  list_matching_instances() {
    printf 'vm-a\tus-central1-a\tRUNNING\tbatch-flaky\tmain\tmain\n'
  }
  read_benchmark_state() {
    case "$(cat "$phase_file")" in
      0) printf 'unreachable\n' ;;
      1) printf 'running\n' ;;
      2) printf 'unreachable\n' ;;
      *) printf 'unreachable\n' ;;
    esac
  }
  resolve_project() { PROJECT="spellbenchmarking"; }
  require_cmd() { :; }
  sleep() {
    printf '%s\n' $(( $(cat "$phase_file") + ${1:-0} )) >"$phase_file"
    SECONDS=$((SECONDS + ${1:-0}))
  }

  local output_file
  output_file="$(mktemp)"
  wait_for_completion >"$output_file"
  local output
  output="$(cat "$output_file")"
  rm -f "$output_file"
  rm -f "$phase_file"

  assert_contains "$output" "0/1 terminal, 0 finished, 0 failed, 1 running, 0 startup, 0 unknown, 0 unreachable" "wait should continue after a recovered VM reports running"
  assert_occurrences "$output" "0/1 terminal, 0 finished, 0 failed, 0 running, 0 startup, 0 unknown, 1 unreachable" "2" "unreachable budget should reset after a successful poll"
  assert_contains "$output" "1/1 terminal, 0 finished, 1 failed, 0 running, 0 startup, 0 unknown, 0 unreachable" "wait should only fail the VM after consecutive unreachable polls"
}

main() {
  test_extract_tar_stream_flattens_paths
  test_render_run_wrapper_contains_status_transitions
  test_filter_instances_json_by_run_group
  test_finish_calls_pull_before_stop
  test_finish_all_skips_active_vms
  test_dispatch_instance_reuses_existing_vm
  test_dispatch_instance_refuses_active_benchmark
  test_wait_for_completion_summarizes_and_finishes
  test_wait_for_completion_times_out
  test_help_and_argument_parsing
  test_startup_script_preserves_claude_auth_and_bootstrap_marker
  test_wait_for_completion_treats_persistent_unreachable_as_terminal
  test_wait_for_completion_resets_unreachable_budget_after_success
  printf 'PASS: gcp benchmark launcher tests\n'
}

main "$@"
