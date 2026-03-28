#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." >/dev/null 2>&1 && pwd)"
STARTUP_SCRIPT="$SCRIPT_DIR/gcp-startup.sh"

STARTUP_OK_MARKER="[spell-benchmark] startup complete"
STARTUP_FAIL_MARKER="[spell-benchmark] startup failed"

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
LOCAL_BENCHMARK_DIR="${SPELL_LOCAL_BENCHMARK_DIR:-$REPO_ROOT/benchmarking}"
AUTO_SSH=1

usage() {
  cat <<'EOF'
Usage:
  ./scripts/gcp-benchmark.sh start [options]
  ./scripts/gcp-benchmark.sh ssh [options]
  ./scripts/gcp-benchmark.sh pull [options]
  ./scripts/gcp-benchmark.sh stop [options]

Commands:
  start   Create the VM, wait for startup, and attach to tmux.
  ssh     Reconnect to the VM tmux session.
  pull    Copy remote benchmarking results, traces, and logs locally.
  stop    Delete the VM.

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
  --local-benchmark-dir PATH      Local benchmarking checkout/path for pull (default: ./benchmarking)
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
  local command="${1:-}"
  if [[ "$command" == "-h" || "$command" == "--help" || "$command" == "help" ]]; then
    usage
    exit 0
  fi
  [[ -n "$command" ]] || {
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
      --local-benchmark-dir)
        LOCAL_BENCHMARK_DIR="$2"
        shift 2
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

  COMMAND="$command"
}

serial_output() {
  gcloud compute instances get-serial-port-output "$INSTANCE_NAME" \
    --project "$PROJECT" \
    --zone "$ZONE" \
    --port 1 2>/dev/null || true
}

wait_for_startup() {
  local deadline=$((SECONDS + STARTUP_TIMEOUT_SECONDS))
  log "waiting for VM startup to finish"

  while (( SECONDS < deadline )); do
    local output
    output="$(serial_output)"
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

  serial_output | tail -n 120 >&2
  die "timed out waiting for startup after ${STARTUP_TIMEOUT_SECONDS}s"
}

ssh_into_vm() {
  log "attaching to tmux session on $INSTANCE_NAME"
  gcloud compute ssh "${REMOTE_USER}@${INSTANCE_NAME}" \
    --project "$PROJECT" \
    --zone "$ZONE" \
    --ssh-flag="-t" \
    --command="tmux new -A -s benchmark -c ~/spell/benchmarking \"bash -lc 'cd ~/spell/benchmarking && exec bash'\""
}

copy_remote_dir() {
  local remote_name="$1"
  local local_base="$2"
  local remote_path="~/spell/benchmarking/${remote_name}"

  mkdir -p "$local_base"
  if ! gcloud compute ssh "${REMOTE_USER}@${INSTANCE_NAME}" \
      --project "$PROJECT" \
      --zone "$ZONE" \
      --command="test -d ${remote_path}"; then
    log "skipping ${remote_name}; remote path not found"
    return 0
  fi

  log "copying ${remote_name} into ${local_base}"
  gcloud compute scp --recurse \
    --project "$PROJECT" \
    --zone "$ZONE" \
    "${REMOTE_USER}@${INSTANCE_NAME}:${remote_path}" \
    "$local_base"
}

start_instance() {
  require_cmd gcloud
  [[ -f "$STARTUP_SCRIPT" ]] || die "missing startup script: $STARTUP_SCRIPT"
  resolve_project

  local network_flags=(--network "$NETWORK")
  if [[ -n "$SUBNET" ]]; then
    network_flags+=(--subnet "$SUBNET")
  fi

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
    --max-run-duration "$MAX_RUN_DURATION" \
    --instance-termination-action DELETE \
    --metadata "benchmark-user=${REMOTE_USER},spell-repo-url=${SPELL_REPO_URL},spell-ref=${SPELL_REF},benchmarking-repo-url=${BENCHMARKING_REPO_URL},benchmarking-ref=${BENCHMARKING_REF},anthropic-secret=${ANTHROPIC_SECRET},openai-secret=${OPENAI_SECRET},github-token-secret=${GITHUB_TOKEN_SECRET}" \
    --metadata-from-file "startup-script=${STARTUP_SCRIPT}"

  wait_for_startup

  if [[ "$AUTO_SSH" -eq 1 ]]; then
    ssh_into_vm
  else
    log "VM is ready; reconnect with: ./scripts/gcp-benchmark.sh ssh --project ${PROJECT} --name ${INSTANCE_NAME} --zone ${ZONE}"
  fi
}

pull_results() {
  require_cmd gcloud
  resolve_project
  mkdir -p "$LOCAL_BENCHMARK_DIR"

  copy_remote_dir "results" "$LOCAL_BENCHMARK_DIR/results/gcp/$INSTANCE_NAME"
  copy_remote_dir "traces" "$LOCAL_BENCHMARK_DIR/traces/gcp/$INSTANCE_NAME"
  copy_remote_dir "logs" "$LOCAL_BENCHMARK_DIR/logs/gcp/$INSTANCE_NAME"
}

stop_instance() {
  require_cmd gcloud
  resolve_project
  log "deleting ${INSTANCE_NAME}"
  gcloud compute instances delete "$INSTANCE_NAME" \
    --project "$PROJECT" \
    --zone "$ZONE" \
    --quiet
}

parse_args "$@"

case "$COMMAND" in
  start)
    start_instance
    ;;
  ssh)
    require_cmd gcloud
    resolve_project
    ssh_into_vm
    ;;
  pull)
    pull_results
    ;;
  stop)
    stop_instance
    ;;
  *)
    die "unknown command: $COMMAND"
    ;;
esac
