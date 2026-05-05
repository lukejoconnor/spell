#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Run orchestration game trials in parallel, then score the shared output root.

Usage:
  scripts/orchestration-games.sh [options] [-- extra clj harness args]

Options:
  --models CSV          Model specs. Default: fireworks:glm-5p1
  --games CSV           Game names or all. Default: all
  --attempts N          Attempts per game/model. Default: 4
  --parallel N|all      Concurrent trial processes. Default: all
  --output-root DIR     Output root. Default: logs/orchestration-games-<timestamp>
  --agent FILE          Override Spell agent config. Default: per-game harness profile
  --prompt-profile P    Prompt profile: scaffold or minimal. Default: scaffold
  --reasoning-effort E  Provider reasoning effort. Default: high
  --budget USD          Per-trial Spell budget; 0 means unlimited. Default: 0
  --depth N             Per-trial recursion depth. Default: 80
  --timeout-sec N       Wall-clock timeout per trial. Default: 900
  --only-missing        Skip trials whose response.json already exists.
  --dry-run             Print commands without calling models.
  -h, --help            Show this help.

Everything after -- is passed through to spell.orchestration-games run.
EOF
}

models="fireworks:glm-5p1"
games="all"
attempts=4
parallel=all
output_root="logs/orchestration-games-$(date +%Y%m%d-%H%M%S)"
agent=""
prompt_profile="scaffold"
reasoning_effort="high"
budget=0
depth=80
timeout_sec=900
only_missing=0
dry_run=0
extra_args=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --models) models="$2"; shift 2 ;;
    --games) games="$2"; shift 2 ;;
    --attempts) attempts="$2"; shift 2 ;;
    --parallel) parallel="$2"; shift 2 ;;
    --output-root) output_root="$2"; shift 2 ;;
    --agent) agent="$2"; shift 2 ;;
    --prompt-profile) prompt_profile="$2"; shift 2 ;;
    --reasoning-effort) reasoning_effort="$2"; shift 2 ;;
    --budget) budget="$2"; shift 2 ;;
    --depth) depth="$2"; shift 2 ;;
    --timeout-sec) timeout_sec="$2"; shift 2 ;;
    --only-missing) only_missing=1; shift ;;
    --dry-run) dry_run=1; shift ;;
    -h|--help) usage; exit 0 ;;
    --) shift; extra_args=("$@"); break ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ "$games" == "all" ]]; then
  game_list=(auction-agents auction-llm-self twenty-questions-agents twenty-questions-llm-self telephone-agents telephone-llm-self)
else
  IFS=',' read -r -a game_list <<< "$games"
fi

IFS=',' read -r -a model_list <<< "$models"
total_trials=$((${#model_list[@]} * ${#game_list[@]} * attempts))
if [[ "$parallel" == "all" ]]; then
  parallel="$total_trials"
fi
if ! [[ "$parallel" =~ ^[0-9]+$ ]] || [[ "$parallel" -lt 1 ]]; then
  echo "--parallel must be a positive integer or 'all'" >&2
  exit 2
fi
mkdir -p "$output_root"

model_label() {
  case "$1" in
    openai-tc:gpt-5.4) echo "gpt54" ;;
    anthropic-tc:claude-opus-4-7) echo "opus47" ;;
    fireworks:glm-5p1) echo "glm51" ;;
    fireworks-tc:glm-5p1) echo "glm51" ;;
    fireworks-tc:kimi-k2p6) echo "kimi26" ;;
    *) echo "$1" | sed -E 's/:/_/g; s/[^A-Za-z0-9_.-]/_/g' ;;
  esac
}

json_escape() {
  python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'
}

write_launcher_error() {
  local game="$1"
  local model="$2"
  local attempt="$3"
  local message="$4"
  local dir="$output_root/runs/$game/$(model_label "$model")/attempt-$(printf '%02d' "$attempt")"
  local escaped_message
  local escaped_dir
  escaped_message="$(printf '%s' "$message" | json_escape)"
  escaped_dir="$(printf '%s' "$dir" | json_escape)"
  mkdir -p "$dir"
  cat > "$dir/response.json" <<EOF
{"ok":false,"error":$escaped_message,"game":"$game","model":"$model","attempt":$attempt,"dir":$escaped_dir,"latency-ms":0}
EOF
}

run_with_timeout() {
  local timeout="$1"
  local stdout_path="$2"
  local stderr_path="$3"
  shift 3

  "$@" > "$stdout_path" 2> "$stderr_path" &
  local child=$!
  local elapsed=0
  while kill -0 "$child" 2>/dev/null; do
    if [[ "$elapsed" -ge "$timeout" ]]; then
      kill "$child" 2>/dev/null || true
      sleep 5
      kill -9 "$child" 2>/dev/null || true
      wait "$child" 2>/dev/null || true
      return 124
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  wait "$child"
}

run_one() {
  local game="$1"
  local model="$2"
  local attempt="$3"
  local log_dir="$output_root/launcher-logs"
  local label
  label="$(echo "${model}_${game}_${attempt}" | tr -c 'A-Za-z0-9_.-' '_')"
  mkdir -p "$log_dir"

  local cmd=(
    clj -M -m spell.orchestration-games run
    --games "$game"
    --models "$model"
    --attempts 1
    --attempt-offset "$attempt"
    --output-root "$output_root"
    --prompt-profile "$prompt_profile"
    --reasoning-effort "$reasoning_effort"
    --budget "$budget"
    --depth "$depth"
    --no-score
  )

  if [[ -n "$agent" ]]; then
    cmd+=(--agent "$agent")
  fi

  if [[ "$only_missing" -eq 1 ]]; then
    cmd+=(--only-missing)
  fi
  if [[ "$dry_run" -eq 1 ]]; then
    cmd+=(--dry-run)
  fi
  if [[ "${#extra_args[@]}" -gt 0 ]]; then
    cmd+=("${extra_args[@]}")
  fi

  printf '%q ' "${cmd[@]}" > "$log_dir/$label.cmd"
  printf '\n' >> "$log_dir/$label.cmd"
  local rc=0
  run_with_timeout "$timeout_sec" "$log_dir/$label.out" "$log_dir/$label.err" "${cmd[@]}" || rc=$?
  if [[ "$rc" -eq 124 ]]; then
    echo "Timed out after ${timeout_sec}s" >> "$log_dir/$label.err"
    write_launcher_error "$game" "$model" "$attempt" "launcher timeout after ${timeout_sec}s"
    return 0
  fi
  if [[ "$rc" -ne 0 ]]; then
    write_launcher_error "$game" "$model" "$attempt" "launcher command failed with exit code $rc"
    return "$rc"
  fi
}

failures=0
pids=()

wait_pid_index() {
  local idx="$1"
  local pid="${pids[$idx]}"
  local rest=()
  local i
  if ! wait "$pid"; then
    failures=$((failures + 1))
  fi
  for ((i = 0; i < ${#pids[@]}; i++)); do
    if [[ "$i" -ne "$idx" ]]; then
      rest+=("${pids[$i]}")
    fi
  done
  if [[ "${#rest[@]}" -gt 0 ]]; then
    pids=("${rest[@]}")
  else
    pids=()
  fi
}

wait_any() {
  local i
  while true; do
    for ((i = 0; i < ${#pids[@]}; i++)); do
      if ! kill -0 "${pids[$i]}" 2>/dev/null; then
        wait_pid_index "$i"
        return
      fi
    done
    sleep 1
  done
}

for model in "${model_list[@]}"; do
  model="$(echo "$model" | xargs)"
  for game in "${game_list[@]}"; do
    game="$(echo "$game" | xargs)"
    for ((attempt = 0; attempt < attempts; attempt++)); do
      run_one "$game" "$model" "$attempt" &
      pids+=("$!")
      if [[ "${#pids[@]}" -ge "$parallel" ]]; then
        wait_any
      fi
    done
  done
done

while [[ "${#pids[@]}" -gt 0 ]]; do
  wait_any
done

if [[ "$dry_run" -eq 0 ]]; then
  clj -M -m spell.orchestration-games score --output-root "$output_root"
fi

mkdir -p workspace
echo "$output_root" > workspace/latest-orchestration-games-run-dir.txt
echo "Output root: $output_root"

if [[ "$failures" -gt 0 ]]; then
  echo "Trial process failures: $failures" >&2
  exit 1
fi
