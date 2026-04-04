#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." >/dev/null 2>&1 && pwd)"
STARTUP_SCRIPT="$SCRIPT_DIR/gcp-startup.sh"

STARTUP_OK_MARKER="[spell-benchmark] startup complete"
STARTUP_FAIL_MARKER="[spell-benchmark] startup failed"
MANAGED_BY_LABEL="spell-benchmark"

INSTANCE_NAME="${SPELL_GCP_INSTANCE:-spell-bench}"
PROJECT="${SPELL_GCP_PROJECT:-}"
ZONE="${SPELL_GCP_ZONE:-us-central1-a}"
MACHINE_TYPE="${SPELL_GCP_MACHINE_TYPE:-e2-standard-4}"
BOOT_DISK_SIZE_GB="${SPELL_GCP_BOOT_DISK_SIZE_GB:-100}"
BOOT_DISK_TYPE="${SPELL_GCP_BOOT_DISK_TYPE:-pd-balanced}"
IMAGE_FAMILY="${SPELL_GCP_IMAGE_FAMILY:-debian-12}"
IMAGE_PROJECT="${SPELL_GCP_IMAGE_PROJECT:-debian-cloud}"
NETWORK="${SPELL_GCP_NETWORK:-default}"
SUBNET="${SPELL_GCP_SUBNET:-}"
MAX_RUN_DURATION="${SPELL_GCP_MAX_RUN_DURATION:-24h}"
STARTUP_TIMEOUT_SECONDS="${SPELL_GCP_STARTUP_TIMEOUT_SECONDS:-1800}"
REMOTE_USER="${SPELL_GCP_REMOTE_USER:-spell}"
SPELL_REPO_URL="${SPELL_GCP_SPELL_REPO_URL:-https://github.com/lukejoconnor/spell.git}"
SPELL_REF="${SPELL_GCP_SPELL_REF:-main}"
BENCHMARKING_REPO_URL="${SPELL_GCP_BENCHMARKING_REPO_URL:-https://github.com/lukejoconnor/spell-benchmarking.git}"
BENCHMARKING_REF="${SPELL_GCP_BENCHMARKING_REF:-main}"
ANTHROPIC_SECRET="${SPELL_GCP_ANTHROPIC_SECRET:-ANTHROPIC_API_KEY}"
OPENAI_SECRET="${SPELL_GCP_OPENAI_SECRET:-OPENAI_API_KEY}"
GITHUB_TOKEN_SECRET="${SPELL_GCP_GITHUB_TOKEN_SECRET:-GITHUB_TOKEN}"
CODEX_AUTH_SECRET="${SPELL_GCP_CODEX_AUTH_SECRET:-CODEX_AUTH_JSON_B64}"
LOCAL_BENCHMARK_DIR="${SPELL_LOCAL_BENCHMARK_DIR:-$REPO_ROOT/benchmarking}"
RUN_GROUP="${SPELL_GCP_RUN_GROUP:-}"
RUN_GROUP_LABEL=""
RUN_COMMAND="${SPELL_GCP_RUN_COMMAND:-}"
OPERATE_ALL=0
FINISHED_ONLY=0
WAIT_INTERVAL_SECONDS="${SPELL_GCP_WAIT_INTERVAL_SECONDS:-120}"
WAIT_TIMEOUT_SECONDS="${SPELL_GCP_WAIT_TIMEOUT_SECONDS:-86400}"
WAIT_AND_FINISH=0
AUTO_SSH=1
START_INSTANCE_CREATED=0
START_INSTANCE_FINISHED=0
ACTION=""

usage() {
  cat <<'EOF'
Usage:
  ./scripts/gcp-benchmark.sh start [options]
  ./scripts/gcp-benchmark.sh run [options] --command "..."
  ./scripts/gcp-benchmark.sh dispatch [options] --command "..."
  ./scripts/gcp-benchmark.sh ssh [options]
  ./scripts/gcp-benchmark.sh status [options]
  ./scripts/gcp-benchmark.sh status-all [--run-group GROUP | --all] [options]
  ./scripts/gcp-benchmark.sh wait [--run-group GROUP | --all] [options]
  ./scripts/gcp-benchmark.sh pull [options]
  ./scripts/gcp-benchmark.sh pull-all [--run-group GROUP | --all] [--finished-only] [options]
  ./scripts/gcp-benchmark.sh finish [options]
  ./scripts/gcp-benchmark.sh finish-all [--run-group GROUP | --all] [options]
  ./scripts/gcp-benchmark.sh stop [options]

Commands:
  start       Create the VM, wait for startup, and attach to tmux.
  run         Create the VM, wait for startup, and launch a benchmark command in tmux.
  dispatch    Launch a benchmark command on an existing Spell benchmark VM.
  ssh         Reconnect to the VM tmux session.
  status      Show one VM's GCP lifecycle state plus benchmark state.
  status-all  List Spell-managed benchmark VMs in the project.
  wait        Poll matched benchmark VMs until they all reach a terminal state.
  pull        Copy remote benchmarking results, traces, and logs locally.
  pull-all    Pull artifacts for all matched Spell-managed benchmark VMs.
  finish      Pull artifacts for one VM, then delete it.
  finish-all  Pull and delete matched VMs whose benchmark state is terminal.
  stop        Delete the VM.

Options:
  --project PROJECT               GCP project ID (defaults to active gcloud project)
  --name NAME                     VM name (default: spell-bench)
  --zone ZONE                     Compute zone (default: us-central1-a)
  --machine-type TYPE             Machine type (default: e2-standard-4)
  --disk-size-gb N                Boot disk size in GB (default: 100)
  --disk-type TYPE                Boot disk type (default: pd-balanced)
  --image-family FAMILY           Image family (default: debian-12)
  --image-project PROJECT         Image project (default: debian-cloud)
  --network NAME                  VPC network name (default: default)
  --subnet NAME                   Optional subnetwork name
  --max-run-duration DURATION     Auto-delete window, e.g. 24h (default: 24h)
  --startup-timeout SECONDS       Wait time for startup (default: 1800)
  --remote-user USER              SSH user created on the VM (default: spell)
  --spell-ref REF                 Git ref to check out for spell (default: main)
  --benchmarking-ref REF          Git ref to check out for spell-benchmarking (default: main)
  --spell-repo-url URL            Override spell repo URL
  --benchmarking-repo-url URL     Override spell-benchmarking repo URL
  --anthropic-secret NAME         Secret Manager secret name (default: ANTHROPIC_API_KEY)
  --openai-secret NAME            Secret Manager secret name (default: OPENAI_API_KEY)
  --github-token-secret NAME      Secret Manager secret name (default: GITHUB_TOKEN)
  --codex-auth-secret NAME        Secret Manager secret name for Codex auth (default: CODEX_AUTH_JSON_B64)
  --local-benchmark-dir PATH      Local benchmarking checkout/path for pull (default: ./benchmarking)
  --run-group GROUP               Logical fleet label for managed VMs (defaults to VM name for single-VM commands)
  --command CMD                   Benchmark command for run
  --all                           Target all Spell-managed benchmark VMs in the project
  --finished-only                 For pull-all, only pull finished/failed VMs
  --interval SECONDS              Poll interval for wait (default: 120)
  --timeout SECONDS               Timeout for wait (default: 86400 / 24h)
  --finish                        For wait, run finish-all before exiting
  --no-ssh                        Create the VM but do not auto-attach after startup
  -h, --help                      Show this help text
EOF
}

log() {
  printf '[gcp-benchmark] %s\n' "$*"
}

die() {
  printf '[gcp-benchmark] error: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

require_positive_integer() {
  local value="$1"
  local name="$2"
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || die "${name} must be a positive integer"
}

join_by() {
  local delimiter="$1"
  shift
  local first=1
  local item
  for item in "$@"; do
    if (( first )); then
      printf '%s' "$item"
      first=0
    else
      printf '%s%s' "$delimiter" "$item"
    fi
  done
}

shell_quote() {
  printf '%q' "$1"
}

slugify_label() {
  local value="$1"
  value="$(printf '%s' "$value" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//; s/-+/-/g')"
  value="${value:0:63}"
  value="$(printf '%s' "$value" | sed -E 's/^-+//; s/-+$//')"
  [[ -n "$value" ]] || value="group"
  printf '%s\n' "$value"
}

resolve_project() {
  if [[ -n "$PROJECT" ]]; then
    return
  fi

  local detected
  detected="$(gcloud config get-value project 2>/dev/null || true)"
  if [[ "$detected" == "(unset)" ]]; then
    detected=""
  fi
  PROJECT="$detected"
  [[ -n "$PROJECT" ]] || die "no GCP project configured; pass --project or run gcloud init"
}

parse_args() {
  local action="${1:-}"
  if [[ "$action" == "-h" || "$action" == "--help" || "$action" == "help" ]]; then
    usage
    exit 0
  fi
  [[ -n "$action" ]] || {
    usage
    exit 1
  }
  shift || true

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --project)
        PROJECT="$2"
        shift 2
        ;;
      --name)
        INSTANCE_NAME="$2"
        shift 2
        ;;
      --zone)
        ZONE="$2"
        shift 2
        ;;
      --machine-type)
        MACHINE_TYPE="$2"
        shift 2
        ;;
      --disk-size-gb)
        BOOT_DISK_SIZE_GB="$2"
        shift 2
        ;;
      --disk-type)
        BOOT_DISK_TYPE="$2"
        shift 2
        ;;
      --image-family)
        IMAGE_FAMILY="$2"
        shift 2
        ;;
      --image-project)
        IMAGE_PROJECT="$2"
        shift 2
        ;;
      --network)
        NETWORK="$2"
        shift 2
        ;;
      --subnet)
        SUBNET="$2"
        shift 2
        ;;
      --max-run-duration)
        MAX_RUN_DURATION="$2"
        shift 2
        ;;
      --startup-timeout)
        STARTUP_TIMEOUT_SECONDS="$2"
        shift 2
        ;;
      --remote-user)
        REMOTE_USER="$2"
        shift 2
        ;;
      --spell-ref)
        SPELL_REF="$2"
        shift 2
        ;;
      --benchmarking-ref)
        BENCHMARKING_REF="$2"
        shift 2
        ;;
      --spell-repo-url)
        SPELL_REPO_URL="$2"
        shift 2
        ;;
      --benchmarking-repo-url)
        BENCHMARKING_REPO_URL="$2"
        shift 2
        ;;
      --anthropic-secret)
        ANTHROPIC_SECRET="$2"
        shift 2
        ;;
      --openai-secret)
        OPENAI_SECRET="$2"
        shift 2
        ;;
      --github-token-secret)
        GITHUB_TOKEN_SECRET="$2"
        shift 2
        ;;
      --codex-auth-secret)
        CODEX_AUTH_SECRET="$2"
        shift 2
        ;;
      --local-benchmark-dir)
        LOCAL_BENCHMARK_DIR="$2"
        shift 2
        ;;
      --run-group)
        RUN_GROUP="$2"
        shift 2
        ;;
      --command)
        RUN_COMMAND="$2"
        shift 2
        ;;
      --all)
        OPERATE_ALL=1
        shift
        ;;
      --finished-only)
        FINISHED_ONLY=1
        shift
        ;;
      --interval)
        WAIT_INTERVAL_SECONDS="$2"
        shift 2
        ;;
      --timeout)
        WAIT_TIMEOUT_SECONDS="$2"
        shift 2
        ;;
      --finish)
        WAIT_AND_FINISH=1
        shift
        ;;
      --no-ssh)
        AUTO_SSH=0
        shift
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        die "unknown option: $1"
        ;;
    esac
  done

  ACTION="$action"
}

validate_args() {
  case "$ACTION" in
    run)
      [[ -n "$RUN_COMMAND" ]] || die "--command is required for run"
      AUTO_SSH=0
      ;;
    dispatch)
      [[ -n "$RUN_COMMAND" ]] || die "--command is required for dispatch"
      ;;
    status-all|wait|pull-all|finish-all)
      if (( OPERATE_ALL == 0 )) && [[ -z "$RUN_GROUP" ]]; then
        die "${ACTION} requires --run-group GROUP or --all"
      fi
      ;;
    start|ssh|status|pull|finish|stop)
      :
      ;;
    *)
      die "unknown command: $ACTION"
      ;;
  esac

  if (( FINISHED_ONLY == 1 )) && [[ "$ACTION" != "pull-all" ]]; then
    die "--finished-only is only supported by pull-all"
  fi

  if (( WAIT_AND_FINISH == 1 )) && [[ "$ACTION" != "wait" ]]; then
    die "--finish is only supported by wait"
  fi

  require_positive_integer "$WAIT_INTERVAL_SECONDS" "--interval"
  require_positive_integer "$WAIT_TIMEOUT_SECONDS" "--timeout"

  case "$ACTION" in
    start|run|dispatch|ssh|status|pull|finish|stop)
      [[ -n "$RUN_GROUP" ]] || RUN_GROUP="$INSTANCE_NAME"
      ;;
  esac

  if [[ -n "$RUN_GROUP" ]]; then
    RUN_GROUP_LABEL="$(slugify_label "$RUN_GROUP")"
  fi
}

serial_output() {
  local instance_name="$1"
  local zone="$2"
  gcloud compute instances get-serial-port-output "$instance_name" \
    --project "$PROJECT" \
    --zone "$zone" \
    --port 1 2>/dev/null || true
}

wait_for_startup() {
  local instance_name="$1"
  local zone="$2"
  local deadline=$((SECONDS + STARTUP_TIMEOUT_SECONDS))
  log "waiting for VM startup to finish"

  while (( SECONDS < deadline )); do
    local output
    output="$(serial_output "$instance_name" "$zone")"
    if grep -Fq "$STARTUP_OK_MARKER" <<<"$output"; then
      log "startup finished successfully"
      return 0
    fi
    if grep -Fq "$STARTUP_FAIL_MARKER" <<<"$output"; then
      printf '%s\n' "$output" | tail -n 120 >&2
      die "startup failed; see serial output above"
    fi
    sleep 10
  done

  serial_output "$instance_name" "$zone" | tail -n 120 >&2
  die "timed out waiting for startup after ${STARTUP_TIMEOUT_SECONDS}s"
}

ssh_into_vm() {
  local instance_name="$1"
  local zone="$2"
  log "attaching to tmux session on $instance_name"
  gcloud compute ssh "${REMOTE_USER}@${instance_name}" \
    --project "$PROJECT" \
    --zone "$zone" \
    --ssh-flag="-t" \
    --command="tmux new -A -s benchmark -c ~/spell/benchmarking \"bash -lc 'cd ~/spell/benchmarking && exec bash'\""
}

cleanup_failed_start() {
  local status=$?
  if (( status != 0 )) && (( START_INSTANCE_CREATED == 1 )) && (( START_INSTANCE_FINISHED == 0 )); then
    log "start failed after VM creation; deleting ${INSTANCE_NAME}"
    if ! gcloud compute instances delete "$INSTANCE_NAME" \
        --project "$PROJECT" \
        --zone "$ZONE" \
        --quiet; then
      log "warning: failed to delete ${INSTANCE_NAME}; clean up manually with ./scripts/gcp-benchmark.sh stop --project ${PROJECT} --name ${INSTANCE_NAME} --zone ${ZONE}"
    fi
  fi
}

metadata_values() {
  join_by "," \
    "benchmark-user=${REMOTE_USER}" \
    "spell-repo-url=${SPELL_REPO_URL}" \
    "spell-ref=${SPELL_REF}" \
    "benchmarking-repo-url=${BENCHMARKING_REPO_URL}" \
    "benchmarking-ref=${BENCHMARKING_REF}" \
    "anthropic-secret=${ANTHROPIC_SECRET}" \
    "openai-secret=${OPENAI_SECRET}" \
    "github-token-secret=${GITHUB_TOKEN_SECRET}" \
    "codex-auth-secret=${CODEX_AUTH_SECRET}" \
    "run-group-label=${RUN_GROUP_LABEL}"
}

create_instance() {
  require_cmd gcloud
  [[ -f "$STARTUP_SCRIPT" ]] || die "missing startup script: $STARTUP_SCRIPT"
  resolve_project

  local network_flags=(--network "$NETWORK")
  if [[ -n "$SUBNET" ]]; then
    network_flags+=(--subnet "$SUBNET")
  fi

  local labels
  labels="$(join_by "," "managed-by=${MANAGED_BY_LABEL}" "run-group=${RUN_GROUP_LABEL}")"

  local metadata_dir
  metadata_dir="$(mktemp -d)"
  local run_group_file="$metadata_dir/run-group"
  local command_file="$metadata_dir/benchmark-command"
  printf '%s' "$RUN_GROUP" >"$run_group_file"
  printf '%s' "$RUN_COMMAND" >"$command_file"

  START_INSTANCE_CREATED=0
  START_INSTANCE_FINISHED=0
  trap cleanup_failed_start EXIT

  log "creating ${INSTANCE_NAME} in ${PROJECT}/${ZONE}"
  gcloud compute instances create "$INSTANCE_NAME" \
    --project "$PROJECT" \
    --zone "$ZONE" \
    --machine-type "$MACHINE_TYPE" \
    --boot-disk-size "${BOOT_DISK_SIZE_GB}GB" \
    --boot-disk-type "$BOOT_DISK_TYPE" \
    --image-family "$IMAGE_FAMILY" \
    --image-project "$IMAGE_PROJECT" \
    "${network_flags[@]}" \
    --scopes cloud-platform \
    --labels "$labels" \
    --max-run-duration "$MAX_RUN_DURATION" \
    --instance-termination-action DELETE \
    --metadata "$(metadata_values)" \
    --metadata-from-file "startup-script=${STARTUP_SCRIPT},run-group=${run_group_file},benchmark-command=${command_file}"
  rm -rf "$metadata_dir"
  START_INSTANCE_CREATED=1
}

refresh_instance_metadata_for_run() {
  local instance_name="$1"
  local zone="$2"
  local labels
  labels="$(join_by "," "managed-by=${MANAGED_BY_LABEL}" "run-group=${RUN_GROUP_LABEL}")"

  local metadata_dir
  metadata_dir="$(mktemp -d)"
  local run_group_file="$metadata_dir/run-group"
  local command_file="$metadata_dir/benchmark-command"
  printf '%s' "$RUN_GROUP" >"$run_group_file"
  printf '%s' "$RUN_COMMAND" >"$command_file"

  gcloud compute instances add-labels "$instance_name" \
    --project "$PROJECT" \
    --zone "$zone" \
    --labels "$labels"

  gcloud compute instances add-metadata "$instance_name" \
    --project "$PROJECT" \
    --zone "$zone" \
    --metadata "$(metadata_values)" \
    --metadata-from-file "run-group=${run_group_file},benchmark-command=${command_file}"

  rm -rf "$metadata_dir"
}

render_run_command_script() {
  cat <<EOF
#!/usr/bin/env bash
set -euo pipefail
source "\$HOME/.profile" 2>/dev/null || true
cd "\$HOME/spell/benchmarking"
${RUN_COMMAND}
EOF
}

render_run_wrapper_script() {
  local run_group_q
  local spell_ref_q
  local benchmarking_ref_q
  local benchmark_command_q
  run_group_q="$(shell_quote "$RUN_GROUP")"
  spell_ref_q="$(shell_quote "$SPELL_REF")"
  benchmarking_ref_q="$(shell_quote "$BENCHMARKING_REF")"
  benchmark_command_q="$(shell_quote "$RUN_COMMAND")"

  cat <<EOF
#!/usr/bin/env bash
set -euo pipefail

STATUS_FILE="\$HOME/.config/spell-benchmark/run-status.json"
COMMAND_FILE="\$HOME/.config/spell-benchmark/run-command.sh"
LOG_FILE="\$HOME/spell/benchmarking/logs/spell-benchmark-run.log"
RUN_GROUP=${run_group_q}
SPELL_REF=${spell_ref_q}
BENCHMARKING_REF=${benchmarking_ref_q}
BENCHMARK_COMMAND=${benchmark_command_q}
CURRENT_STATE="starting"

write_status() {
  local state="\$1"
  local exit_code="\${2:-}"
  local error_message="\${3:-}"
  CURRENT_STATE="\$state"
  STATE="\$state" EXIT_CODE="\$exit_code" ERROR_MESSAGE="\$error_message" \\
  RUN_GROUP="\$RUN_GROUP" SPELL_REF="\$SPELL_REF" BENCHMARKING_REF="\$BENCHMARKING_REF" \\
  BENCHMARK_COMMAND="\$BENCHMARK_COMMAND" LOG_FILE="\$LOG_FILE" \\
  python3 - <<'PY' >"\$STATUS_FILE"
import json
import os
from datetime import datetime, timezone

payload = {
    "state": os.environ["STATE"],
    "exit_code": int(os.environ["EXIT_CODE"]) if os.environ["EXIT_CODE"] else None,
    "error_message": os.environ["ERROR_MESSAGE"] or None,
    "run_group": os.environ["RUN_GROUP"],
    "spell_ref": os.environ["SPELL_REF"],
    "benchmarking_ref": os.environ["BENCHMARKING_REF"],
    "command": os.environ["BENCHMARK_COMMAND"],
    "log_file": os.environ["LOG_FILE"],
    "updated_at": datetime.now(timezone.utc).isoformat(),
}
print(json.dumps(payload, indent=2, sort_keys=True))
PY
}

on_exit() {
  local status=\$?
  if (( status != 0 )) && [[ "\$CURRENT_STATE" != "failed" && "\$CURRENT_STATE" != "finished" ]]; then
    write_status failed "\$status" "wrapper aborted"
  fi
  exit "\$status"
}
trap on_exit EXIT

mkdir -p "\$(dirname "\$STATUS_FILE")" "\$(dirname "\$LOG_FILE")"
write_status starting
exec >>"\$LOG_FILE" 2>&1

printf '[spell-benchmark] run wrapper started at %s\n' "\$(date -u +%Y-%m-%dT%H:%M:%SZ)"
write_status running

set +e
bash "\$COMMAND_FILE"
exit_code=\$?
set -e

if (( exit_code == 0 )); then
  write_status finished "\$exit_code"
else
  write_status failed "\$exit_code"
fi

exit "\$exit_code"
EOF
}

render_run_launch_script() {
  cat <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

tmux has-session -t benchmark 2>/dev/null || \
  tmux new-session -d -s benchmark -c "$HOME/spell/benchmarking" "bash -lc 'cd \"$HOME/spell/benchmarking\" && exec bash'"
tmux kill-window -t benchmark:spell-run >/dev/null 2>&1 || true
tmux new-window -d -t benchmark -n spell-run -c "$HOME/spell/benchmarking" "$HOME/.config/spell-benchmark/run-wrapper.sh"
EOF
}

stage_remote_run_scripts() {
  local instance_name="$1"
  local zone="$2"
  local remote_home="/home/${REMOTE_USER}"
  local remote_config_dir="${remote_home}/.config/spell-benchmark"

  local staging_dir
  staging_dir="$(mktemp -d)"
  local command_script="$staging_dir/run-command.sh"
  local wrapper_script="$staging_dir/run-wrapper.sh"
  local launch_script="$staging_dir/launch-run.sh"

  render_run_command_script >"$command_script"
  render_run_wrapper_script >"$wrapper_script"
  render_run_launch_script >"$launch_script"
  chmod 700 "$command_script" "$wrapper_script" "$launch_script"

  gcloud compute ssh "${REMOTE_USER}@${instance_name}" \
    --project "$PROJECT" \
    --zone "$zone" \
    --command="mkdir -p ${remote_config_dir} ${remote_home}/spell/benchmarking/logs"

  gcloud compute scp \
    --project "$PROJECT" \
    --zone "$zone" \
    "$command_script" \
    "$wrapper_script" \
    "$launch_script" \
    "${REMOTE_USER}@${instance_name}:${remote_config_dir}/"

  gcloud compute ssh "${REMOTE_USER}@${instance_name}" \
    --project "$PROJECT" \
    --zone "$zone" \
    --command="chmod 700 ${remote_config_dir}/run-command.sh ${remote_config_dir}/run-wrapper.sh ${remote_config_dir}/launch-run.sh"

  rm -rf "$staging_dir"
}

launch_benchmark_command() {
  local instance_name="$1"
  local zone="$2"

  log "staging benchmark wrapper on ${instance_name}"
  stage_remote_run_scripts "$instance_name" "$zone"
  gcloud compute ssh "${REMOTE_USER}@${instance_name}" \
    --project "$PROJECT" \
    --zone "$zone" \
    --command="bash /home/${REMOTE_USER}/.config/spell-benchmark/launch-run.sh"
  log "benchmark launched on ${instance_name}; use ./scripts/gcp-benchmark.sh status --project ${PROJECT} --name ${instance_name} --zone ${zone}"
}

start_instance() {
  create_instance
  wait_for_startup "$INSTANCE_NAME" "$ZONE"

  if [[ "$AUTO_SSH" -eq 1 ]]; then
    ssh_into_vm "$INSTANCE_NAME" "$ZONE"
  else
    log "VM is ready; reconnect with: ./scripts/gcp-benchmark.sh ssh --project ${PROJECT} --name ${INSTANCE_NAME} --zone ${ZONE}"
  fi

  START_INSTANCE_FINISHED=1
  trap - EXIT
}

run_instance() {
  create_instance
  wait_for_startup "$INSTANCE_NAME" "$ZONE"
  launch_benchmark_command "$INSTANCE_NAME" "$ZONE"
  START_INSTANCE_FINISHED=1
  trap - EXIT
}

dispatch_instance() {
  require_cmd gcloud
  require_cmd python3
  resolve_project

  local instance_json
  if ! instance_json="$(describe_instance_json "$INSTANCE_NAME" "$ZONE")" || [[ -z "$instance_json" ]]; then
    die "instance ${INSTANCE_NAME} not found in ${PROJECT}/${ZONE}; create it with start or run first"
  fi

  local zone
  local gcp_state
  local run_group
  local spell_ref
  local benchmarking_ref
  local command
  IFS=$'\t' read -r zone gcp_state run_group spell_ref benchmarking_ref command <<<"$(printf '%s' "$instance_json" | instance_summary_row_from_json)"

  case "$gcp_state" in
    PROVISIONING|STAGING|RUNNING)
      :
      ;;
    *)
      die "instance ${INSTANCE_NAME} is not dispatchable in GCP state ${gcp_state}"
      ;;
  esac

  wait_for_startup "$INSTANCE_NAME" "$zone"

  local benchmark_state
  benchmark_state="$(read_benchmark_state "$INSTANCE_NAME" "$zone" "RUNNING")"
  case "$benchmark_state" in
    running|startup)
      die "instance ${INSTANCE_NAME} already has an active benchmark (${benchmark_state}); wait or finish it first"
      ;;
  esac

  refresh_instance_metadata_for_run "$INSTANCE_NAME" "$zone"
  launch_benchmark_command "$INSTANCE_NAME" "$zone"
}

extract_tar_stream_into_dir() {
  local local_base="$1"
  mkdir -p "$local_base"
  tar -xf - -C "$local_base"
}

remote_dir_presence() {
  local instance_name="$1"
  local zone="$2"
  local remote_name="$3"

  gcloud compute ssh "${REMOTE_USER}@${instance_name}" \
    --project "$PROJECT" \
    --zone "$zone" \
    --command="bash -lc 'if [[ -d \$HOME/spell/benchmarking/${remote_name} ]]; then echo exists; else echo missing; fi'" < /dev/null
}

copy_remote_dir_from_instance() {
  local instance_name="$1"
  local zone="$2"
  local remote_name="$3"
  local local_base="$4"

  mkdir -p "$local_base"
  local presence
  if ! presence="$(remote_dir_presence "$instance_name" "$zone" "$remote_name")"; then
    log "failed to inspect ${remote_name} on ${instance_name}"
    return 1
  fi
  if [[ "$presence" != "exists" ]]; then
    log "skipping ${remote_name} for ${instance_name}; remote path not found"
    return 0
  fi

  log "copying ${remote_name} from ${instance_name} into ${local_base}"
  gcloud compute ssh "${REMOTE_USER}@${instance_name}" \
    --project "$PROJECT" \
    --zone "$zone" \
    --command="bash -lc 'cd \$HOME/spell/benchmarking/${remote_name} && tar -cf - .'" < /dev/null \
    | extract_tar_stream_into_dir "$local_base"
}

pull_results_from_instance() {
  local instance_name="$1"
  local zone="$2"
  require_cmd gcloud
  resolve_project
  mkdir -p "$LOCAL_BENCHMARK_DIR"

  copy_remote_dir_from_instance "$instance_name" "$zone" "results" "$LOCAL_BENCHMARK_DIR/results/gcp/$instance_name"
  copy_remote_dir_from_instance "$instance_name" "$zone" "traces" "$LOCAL_BENCHMARK_DIR/traces/gcp/$instance_name"
  copy_remote_dir_from_instance "$instance_name" "$zone" "logs" "$LOCAL_BENCHMARK_DIR/logs/gcp/$instance_name"
}

stop_instance_named() {
  local instance_name="$1"
  local zone="$2"
  require_cmd gcloud
  resolve_project
  log "deleting ${instance_name}"
  gcloud compute instances delete "$instance_name" \
    --project "$PROJECT" \
    --zone "$zone" \
    --quiet
}

finish_instance_named() {
  local instance_name="$1"
  local zone="$2"
  pull_results_from_instance "$instance_name" "$zone"
  stop_instance_named "$instance_name" "$zone"
}

list_all_managed_instances_json() {
  gcloud compute instances list \
    --project "$PROJECT" \
    --filter "labels.managed-by=${MANAGED_BY_LABEL}" \
    --format=json
}

filter_instances_json_by_run_group() {
  local run_group_label="${1:-}"
  local run_group="${2:-}"
  python3 -c '
import json
import sys

run_group_label = sys.argv[1]
run_group = sys.argv[2]
operate_all = sys.argv[3] == "1"
instances = json.load(sys.stdin)

def metadata_value(instance, key):
    for item in instance.get("metadata", {}).get("items", []):
        if item.get("key") == key:
            return item.get("value", "")
    return ""

if operate_all:
    filtered = instances
else:
    filtered = [
        item for item in instances
        if item.get("labels", {}).get("run-group") == run_group_label
        and metadata_value(item, "run-group") == run_group
    ]
print(json.dumps(filtered))
' "$run_group_label" "$run_group" "$OPERATE_ALL"
}

instance_rows_from_json() {
  python3 -c '
import json
import sys

instances = json.load(sys.stdin)
for item in instances:
    metadata = {
        entry.get("key"): entry.get("value", "")
        for entry in item.get("metadata", {}).get("items", [])
    }
    zone = item.get("zone", "").rsplit("/", 1)[-1]
    row = [
        item.get("name", ""),
        zone,
        item.get("status", ""),
        metadata.get("run-group", ""),
        metadata.get("spell-ref", ""),
        metadata.get("benchmarking-ref", ""),
    ]
    print("\t".join(field.replace("\t", " ") for field in row))
'
}

list_matching_instances() {
  local instances_json
  local filtered_json
  instances_json="$(list_all_managed_instances_json)"
  filtered_json="$(printf '%s' "$instances_json" | filter_instances_json_by_run_group "$RUN_GROUP_LABEL" "$RUN_GROUP")"
  printf '%s' "$filtered_json" | instance_rows_from_json
}

describe_instance_json() {
  local instance_name="$1"
  local zone="$2"
  gcloud compute instances describe "$instance_name" \
    --project "$PROJECT" \
    --zone "$zone" \
    --format=json 2>/dev/null
}

instance_summary_row_from_json() {
  python3 -c '
import json
import sys

instance = json.load(sys.stdin)
metadata = {
    entry.get("key"): entry.get("value", "")
    for entry in instance.get("metadata", {}).get("items", [])
}
command = metadata.get("benchmark-command", "").replace("\n", "\\n").replace("\t", " ")
row = [
    instance.get("zone", "").rsplit("/", 1)[-1],
    instance.get("status", ""),
    metadata.get("run-group", ""),
    metadata.get("spell-ref", ""),
    metadata.get("benchmarking-ref", ""),
    command,
]
print("\t".join(row))
'
}

read_remote_status_json() {
  local instance_name="$1"
  local zone="$2"
  gcloud compute ssh "${REMOTE_USER}@${instance_name}" \
    --project "$PROJECT" \
    --zone "$zone" \
    --command="bash -lc 'cat \$HOME/.config/spell-benchmark/run-status.json'" < /dev/null 2>/dev/null
}

status_row_from_json() {
  python3 -c '
import json
import sys

status = json.load(sys.stdin)
row = [
    status.get("state") or "-",
    "-" if status.get("exit_code") is None else str(status.get("exit_code")),
    (status.get("log_file") or "-").replace("\t", " "),
    (status.get("updated_at") or "-").replace("\t", " "),
    (status.get("command") or "-").replace("\n", "\\n").replace("\t", " "),
]
print("\t".join(row))
'
}

startup_complete_for_instance() {
  local instance_name="$1"
  local zone="$2"
  local output
  output="$(serial_output "$instance_name" "$zone")"
  grep -Fq "$STARTUP_OK_MARKER" <<<"$output"
}

read_benchmark_state() {
  local instance_name="$1"
  local zone="$2"
  local gcp_state="$3"

  case "$gcp_state" in
    PROVISIONING|STAGING)
      printf '%s\n' "startup"
      return 0
      ;;
    RUNNING)
      :
      ;;
    *)
      printf '%s\n' "unknown"
      return 0
      ;;
  esac

  local status_json
  if status_json="$(read_remote_status_json "$instance_name" "$zone")" && [[ -n "$status_json" ]]; then
    printf '%s' "$status_json" | python3 -c 'import json, sys; print(json.load(sys.stdin).get("state", "unknown"))'
    return 0
  fi

  if startup_complete_for_instance "$instance_name" "$zone"; then
    printf '%s\n' "unknown"
  else
    printf '%s\n' "startup"
  fi
}

show_status() {
  require_cmd gcloud
  require_cmd python3
  resolve_project

  local instance_json
  if ! instance_json="$(describe_instance_json "$INSTANCE_NAME" "$ZONE")"; then
    printf 'Instance: %s\nZone: %s\nGCP: deleted\nBenchmark: unknown\n' "$INSTANCE_NAME" "$ZONE"
    return 0
  fi

  local zone
  local gcp_state
  local run_group
  local spell_ref
  local benchmarking_ref
  local command
  IFS=$'\t' read -r zone gcp_state run_group spell_ref benchmarking_ref command <<<"$(printf '%s' "$instance_json" | instance_summary_row_from_json)"

  local benchmark_state
  benchmark_state="$(read_benchmark_state "$INSTANCE_NAME" "$zone" "$gcp_state")"

  printf 'Instance: %s\n' "$INSTANCE_NAME"
  printf 'Zone: %s\n' "$zone"
  printf 'GCP: %s\n' "$gcp_state"
  printf 'Benchmark: %s\n' "$benchmark_state"
  [[ -n "$run_group" ]] && printf 'Run group: %s\n' "$run_group"
  [[ -n "$spell_ref" ]] && printf 'Spell ref: %s\n' "$spell_ref"
  [[ -n "$benchmarking_ref" ]] && printf 'Benchmarking ref: %s\n' "$benchmarking_ref"

  local status_json
  if status_json="$(read_remote_status_json "$INSTANCE_NAME" "$zone")" && [[ -n "$status_json" ]]; then
    local state
    local exit_code
    local log_file
    local updated_at
    local command_from_status
    IFS=$'\t' read -r state exit_code log_file updated_at command_from_status <<<"$(printf '%s' "$status_json" | status_row_from_json)"
    [[ "$command_from_status" != "-" && -n "$command_from_status" ]] && command="$command_from_status"
    [[ "$exit_code" != "-" && -n "$exit_code" ]] && printf 'Exit code: %s\n' "$exit_code"
    [[ "$log_file" != "-" && -n "$log_file" ]] && printf 'Log file: %s\n' "$log_file"
    [[ "$updated_at" != "-" && -n "$updated_at" ]] && printf 'Updated at: %s\n' "$updated_at"
  fi

  [[ -n "$command" ]] && printf 'Command: %s\n' "$command"
}

status_all_instances() {
  require_cmd gcloud
  require_cmd python3
  resolve_project

  local rows
  rows="$(list_matching_instances)"
  if [[ -z "$rows" ]]; then
    log "no matching Spell-managed benchmark VMs found"
    return 0
  fi

  printf '%-28s %-18s %-14s %-12s %s\n' "INSTANCE" "ZONE" "GCP" "BENCHMARK" "RUN GROUP"
  while IFS=$'\t' read -r instance_name zone gcp_state run_group spell_ref benchmarking_ref; do
    local benchmark_state
    benchmark_state="$(read_benchmark_state "$instance_name" "$zone" "$gcp_state")"
    printf '%-28s %-18s %-14s %-12s %s\n' "$instance_name" "$zone" "$gcp_state" "$benchmark_state" "$run_group"
  done <<<"$rows"
}

wait_for_completion() {
  require_cmd gcloud
  require_cmd python3
  resolve_project

  local deadline=$((SECONDS + WAIT_TIMEOUT_SECONDS))
  local saw_matches=0

  while true; do
    local rows
    rows="$(list_matching_instances)"

    local total=0
    local finished=0
    local failed=0
    local running=0
    local startup=0
    local unknown=0

    if [[ -n "$rows" ]]; then
      saw_matches=1
      while IFS=$'\t' read -r instance_name zone gcp_state run_group spell_ref benchmarking_ref; do
        [[ -n "$instance_name" ]] || continue
        total=$((total + 1))

        local benchmark_state
        benchmark_state="$(read_benchmark_state "$instance_name" "$zone" "$gcp_state")"
        case "$benchmark_state" in
          finished)
            finished=$((finished + 1))
            ;;
          failed)
            failed=$((failed + 1))
            ;;
          running)
            running=$((running + 1))
            ;;
          startup)
            startup=$((startup + 1))
            ;;
          *)
            unknown=$((unknown + 1))
            ;;
        esac
      done <<<"$rows"
    fi

    local terminal=$((finished + failed))
    local timestamp
    timestamp="$(date -u +%H:%M:%S)"

    if (( total == 0 )); then
      if (( saw_matches == 0 )); then
        die "wait found no matching Spell-managed benchmark VMs"
      fi
      log "0 matching VMs remain (${timestamp} UTC); treating wait as complete"
      return 0
    fi

    log "${terminal}/${total} terminal, ${finished} finished, ${failed} failed, ${running} running, ${startup} startup, ${unknown} unknown (${timestamp} UTC)"

    if (( terminal == total )); then
      if (( WAIT_AND_FINISH == 1 )); then
        finish_all_instances
      fi
      return 0
    fi

    local remaining=$((deadline - SECONDS))
    if (( remaining <= 0 )); then
      die "timed out after ${WAIT_TIMEOUT_SECONDS}s waiting for benchmark completion"
    fi

    local sleep_seconds="$WAIT_INTERVAL_SECONDS"
    if (( remaining < sleep_seconds )); then
      sleep_seconds="$remaining"
    fi
    sleep "$sleep_seconds"
  done
}

pull_all_instances() {
  require_cmd gcloud
  require_cmd python3
  resolve_project

  local rows
  rows="$(list_matching_instances)"
  if [[ -z "$rows" ]]; then
    log "no matching Spell-managed benchmark VMs found"
    return 0
  fi

  local pulled=0
  local failed=0
  local skipped=0
  local pulled_names=()
  local failed_names=()
  local skipped_names=()
  while IFS=$'\t' read -r instance_name zone gcp_state run_group spell_ref benchmarking_ref; do
    local benchmark_state
    benchmark_state="$(read_benchmark_state "$instance_name" "$zone" "$gcp_state")"
    if (( FINISHED_ONLY == 1 )) && [[ "$benchmark_state" != "finished" && "$benchmark_state" != "failed" ]]; then
      skipped=$((skipped + 1))
      skipped_names+=("$instance_name")
      continue
    fi
    if pull_results_from_instance "$instance_name" "$zone"; then
      pulled=$((pulled + 1))
      pulled_names+=("$instance_name")
    else
      failed=$((failed + 1))
      failed_names+=("$instance_name")
    fi
  done <<<"$rows"

  log "pull-all summary: pulled=${pulled} failed=${failed} skipped=${skipped}"
  (( ${#pulled_names[@]} > 0 )) && log "pulled: $(join_by ', ' "${pulled_names[@]}")"
  (( ${#failed_names[@]} > 0 )) && log "pull failures: $(join_by ', ' "${failed_names[@]}")"
  (( ${#skipped_names[@]} > 0 )) && log "skipped: $(join_by ', ' "${skipped_names[@]}")"
  (( failed == 0 ))
}

finish_all_instances() {
  require_cmd gcloud
  require_cmd python3
  resolve_project

  local rows
  rows="$(list_matching_instances)"
  if [[ -z "$rows" ]]; then
    log "no matching Spell-managed benchmark VMs found"
    return 0
  fi

  local finished=0
  local failed=0
  local skipped=0
  local finished_names=()
  local failed_names=()
  local skipped_names=()
  while IFS=$'\t' read -r instance_name zone gcp_state run_group spell_ref benchmarking_ref; do
    local benchmark_state
    benchmark_state="$(read_benchmark_state "$instance_name" "$zone" "$gcp_state")"
    if [[ "$benchmark_state" != "finished" && "$benchmark_state" != "failed" ]]; then
      skipped=$((skipped + 1))
      skipped_names+=("${instance_name}:${benchmark_state}")
      continue
    fi
    if finish_instance_named "$instance_name" "$zone"; then
      finished=$((finished + 1))
      finished_names+=("$instance_name")
    else
      failed=$((failed + 1))
      failed_names+=("$instance_name")
    fi
  done <<<"$rows"

  log "finish-all summary: finished=${finished} failed=${failed} skipped=${skipped}"
  (( ${#finished_names[@]} > 0 )) && log "finished: $(join_by ', ' "${finished_names[@]}")"
  (( ${#failed_names[@]} > 0 )) && log "finish failures: $(join_by ', ' "${failed_names[@]}")"
  (( ${#skipped_names[@]} > 0 )) && log "skipped active VMs: $(join_by ', ' "${skipped_names[@]}")"
  (( failed == 0 ))
}

main() {
  parse_args "$@"
  validate_args

  case "$ACTION" in
    start)
      start_instance
      ;;
    run)
      run_instance
      ;;
    dispatch)
      dispatch_instance
      ;;
    ssh)
      require_cmd gcloud
      resolve_project
      ssh_into_vm "$INSTANCE_NAME" "$ZONE"
      ;;
    status)
      show_status
      ;;
    status-all)
      status_all_instances
      ;;
    wait)
      wait_for_completion
      ;;
    pull)
      pull_results_from_instance "$INSTANCE_NAME" "$ZONE"
      ;;
    pull-all)
      pull_all_instances
      ;;
    finish)
      finish_instance_named "$INSTANCE_NAME" "$ZONE"
      ;;
    finish-all)
      finish_all_instances
      ;;
    stop)
      stop_instance_named "$INSTANCE_NAME" "$ZONE"
      ;;
    *)
      die "unknown command: $ACTION"
      ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
