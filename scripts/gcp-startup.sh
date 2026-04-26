#!/usr/bin/env bash
set -euo pipefail

LOG_FILE=/var/log/spell-benchmark-startup.log
STARTUP_OK_MARKER="[spell-benchmark] startup complete"
STARTUP_FAIL_MARKER="[spell-benchmark] startup failed"

mkdir -p "$(dirname "$LOG_FILE")"
exec > >(tee -a "$LOG_FILE" /dev/ttyS0) 2>&1

trap 'status=$?; if (( status != 0 )); then echo "$STARTUP_FAIL_MARKER (exit ${status})"; fi; exit "$status"' EXIT

METADATA_ROOT="http://metadata.google.internal/computeMetadata/v1"

metadata_get() {
  local url="${METADATA_ROOT}/$1"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -H "Metadata-Flavor: Google" "$url"
    return 0
  fi
  if command -v python3 >/dev/null 2>&1; then
    METADATA_URL="$url" python3 - <<'PY'
import os
import sys
import urllib.request

request = urllib.request.Request(
    os.environ["METADATA_URL"],
    headers={"Metadata-Flavor": "Google"},
)
with urllib.request.urlopen(request) as response:
    sys.stdout.write(response.read().decode())
PY
    return 0
  fi
  echo "curl or python3 is required to read GCP instance metadata" >&2
  return 1
}

metadata_attr() {
  metadata_get "instance/attributes/$1"
}

project_id() {
  metadata_get "project/project-id"
}

access_token() {
  metadata_get "instance/service-accounts/default/token" | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])'
}

fetch_secret() {
  local project="$1"
  local secret_name="$2"
  local token
  token="$(access_token)"
  curl -fsSL \
    -H "Authorization: Bearer ${token}" \
    "https://secretmanager.googleapis.com/v1/projects/${project}/secrets/${secret_name}/versions/latest:access" \
    | python3 -c 'import base64, json, sys; print(base64.b64decode(json.load(sys.stdin)["payload"]["data"]).decode())'
}

run_as_benchmark_user() {
  local command="$1"
  runuser -u "$BENCHMARK_USER" -- bash -lc "$command"
}

write_run_status() {
  local state="$1"
  local exit_code="${2:-}"
  local error_message="${3:-}"
  mkdir -p "$USER_HOME/.config/spell-benchmark"
  STATE="$state" EXIT_CODE="$exit_code" ERROR_MESSAGE="$error_message" \
  RUN_GROUP="$RUN_GROUP" SPELL_REF="$SPELL_REF" BENCHMARKING_REF="$BENCHMARKING_REF" \
  BENCHMARK_COMMAND="$BENCHMARK_COMMAND" LOG_FILE="$USER_HOME/spell/benchmarking/logs/spell-benchmark-run.log" \
  python3 - <<'PY' >"$USER_HOME/.config/spell-benchmark/run-status.json"
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
  chown -R "$BENCHMARK_USER:$BENCHMARK_USER" "$USER_HOME/.config"
}

read_run_status_field() {
  local field_name="$1"
  local status_file="$USER_HOME/.config/spell-benchmark/run-status.json"
  [[ -f "$status_file" ]] || return 1
  STATUS_FILE="$status_file" FIELD_NAME="$field_name" python3 - <<'PY'
import json
import os
import sys

with open(os.environ["STATUS_FILE"]) as handle:
    payload = json.load(handle)

value = payload.get(os.environ["FIELD_NAME"])
if value is None:
    sys.exit(1)
print(value)
PY
}

append_once() {
  local file="$1"
  local marker="$2"
  local content="$3"
  if [[ -f "$file" ]] && grep -Fq "$marker" "$file"; then
    return 0
  fi
  printf '\n%s\n' "$content" >>"$file"
}

install_clojure_cli() {
  if command -v clojure >/dev/null 2>&1 && clojure -Sdescribe >/dev/null 2>&1; then
    return 0
  fi
  if command -v clj >/dev/null 2>&1; then
    return 0
  fi

  local tmpdir
  tmpdir="$(mktemp -d)"
  curl -fsSL https://download.clojure.org/install/linux-install-1.11.1.1413.sh -o "${tmpdir}/install-clojure.sh"
  chmod +x "${tmpdir}/install-clojure.sh"
  "${tmpdir}/install-clojure.sh"
  rm -rf "$tmpdir"
}

install_node_tooling() {
  run_as_benchmark_user '
    set -euo pipefail
    if [[ ! -s "$HOME/.nvm/nvm.sh" ]]; then
      curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.2/install.sh | bash
    fi
    source "$HOME/.nvm/nvm.sh"
    nvm install 22
    npm install -g @anthropic-ai/claude-code@latest @openai/codex@latest
  '
}

install_uv_and_python() {
  run_as_benchmark_user '
    set -euo pipefail
    export PATH="$HOME/.local/bin:$PATH"
    if ! command -v uv >/dev/null 2>&1; then
      curl -LsSf https://astral.sh/uv/install.sh | sh
    fi
    export PATH="$HOME/.local/bin:$PATH"
    uv python install 3.13
    if ! command -v tb >/dev/null 2>&1; then
      uv tool install terminal-bench
    fi
  '
}

clone_repo() {
  local repo_url="$1"
  local dest_dir="$2"
  local ref="$3"
  local github_token="$4"
  local git_args=()

  if [[ -n "$github_token" && "$repo_url" == https://github.com/* ]]; then
    local auth_header
    auth_header="$(printf 'x-access-token:%s' "$github_token" | base64 | tr -d '\n')"
    git_args=(-c "http.extraheader=AUTHORIZATION: basic ${auth_header}")
  fi

  git "${git_args[@]}" clone "$repo_url" "$dest_dir"
  git -C "$dest_dir" remote set-url origin "$repo_url"
  if [[ -n "$ref" && "$ref" != "HEAD" ]]; then
    git -C "$dest_dir" "${git_args[@]}" fetch origin "$ref" --depth 1
    git -C "$dest_dir" checkout FETCH_HEAD
  fi
}

wait_for_docker() {
  local attempts=0
  until docker info >/dev/null 2>&1; do
    attempts=$((attempts + 1))
    if (( attempts >= 30 )); then
      echo "docker did not become ready in time" >&2
      return 1
    fi
    sleep 2
  done
}

materialize_secrets_and_env() {
  echo "[spell-benchmark] fetching secrets from Secret Manager"
  local anthropic_api_key
  local openai_api_key
  local github_token
  local codex_auth_b64=""
  local cc_oauth_token=""

  anthropic_api_key="$(fetch_secret "$PROJECT_ID" "$ANTHROPIC_SECRET")"
  openai_api_key="$(fetch_secret "$PROJECT_ID" "$OPENAI_SECRET")"
  github_token="$(fetch_secret "$PROJECT_ID" "$GITHUB_TOKEN_SECRET")"
  if [[ -n "$CODEX_AUTH_SECRET" ]]; then
    codex_auth_b64="$(fetch_secret "$PROJECT_ID" "$CODEX_AUTH_SECRET" 2>/dev/null || true)"
  fi
  if [[ -n "$CC_OAUTH_SECRET" ]]; then
    cc_oauth_token="$(fetch_secret "$PROJECT_ID" "$CC_OAUTH_SECRET" 2>/dev/null || true)"
  fi

  mkdir -p "$USER_HOME/.config/spell-benchmark"
  cat >"$USER_HOME/.config/spell-benchmark/env.sh" <<EOF
export SPELL_ROOT="$USER_HOME/spell"
export ANTHROPIC_API_KEY=$(printf '%q' "$anthropic_api_key")
export OPENAI_API_KEY=$(printf '%q' "$openai_api_key")
export HF_HUB_ETAG_TIMEOUT=30
export HF_HUB_DOWNLOAD_TIMEOUT=60
export HF_HUB_ENABLE_HF_TRANSFER=0
EOF
  if [[ -n "$codex_auth_b64" ]]; then
    printf 'export CODEX_AUTH_JSON_B64=%q\n' "$codex_auth_b64" >>"$USER_HOME/.config/spell-benchmark/env.sh"
    mkdir -p "$USER_HOME/.codex"
    printf '%s' "$codex_auth_b64" | base64 -d >"$USER_HOME/.codex/auth.json"
    chmod 600 "$USER_HOME/.codex/auth.json"
    chown -R "$BENCHMARK_USER:$BENCHMARK_USER" "$USER_HOME/.codex"
  fi
  rm -f "$USER_HOME/.claude.json"
  if [[ -n "$cc_oauth_token" ]]; then
    printf 'export CLAUDE_CODE_OAUTH_TOKEN=%q\n' "$cc_oauth_token" >>"$USER_HOME/.config/spell-benchmark/env.sh"
  else
    echo "[spell-benchmark] warning: Secret Manager secret ${CC_OAUTH_SECRET:-CLAUDE_CODE_OAUTH_TOKEN} was unavailable or empty; Claude Code benchmark runs require CLAUDE_CODE_OAUTH_TOKEN" >&2
  fi
  chmod 600 "$USER_HOME/.config/spell-benchmark/env.sh"
  chown -R "$BENCHMARK_USER:$BENCHMARK_USER" "$USER_HOME/.config"

  append_once "$USER_HOME/.bashrc" "# spell-benchmark env" \
    '# spell-benchmark env
export PATH="$HOME/.local/bin:$PATH"
if [[ -s "$HOME/.nvm/nvm.sh" ]]; then
  source "$HOME/.nvm/nvm.sh"
fi
if [[ -f "$HOME/.config/spell-benchmark/env.sh" ]]; then
  source "$HOME/.config/spell-benchmark/env.sh"
fi'
  append_once "$USER_HOME/.profile" "# spell-benchmark env" \
    '# spell-benchmark env
export PATH="$HOME/.local/bin:$PATH"
if [[ -s "$HOME/.nvm/nvm.sh" ]]; then
  source "$HOME/.nvm/nvm.sh"
fi
if [[ -f "$HOME/.config/spell-benchmark/env.sh" ]]; then
  source "$HOME/.config/spell-benchmark/env.sh"
fi
if [[ -f "$HOME/.bashrc" ]]; then
  source "$HOME/.bashrc"
fi'
  chown "$BENCHMARK_USER:$BENCHMARK_USER" "$USER_HOME/.bashrc" "$USER_HOME/.profile"

  GITHUB_TOKEN="$github_token"
}

ensure_tmux_session() {
  run_as_benchmark_user '
    set -euo pipefail
    tmux has-session -t benchmark 2>/dev/null || \
      tmux new-session -d -s benchmark -c "$HOME/spell/benchmarking" "bash -lc '\''cd \"$HOME/spell/benchmarking\" && exec bash'\''"
  '
}

mark_interrupted_run_if_needed() {
  local prior_state=""
  prior_state="$(read_run_status_field state 2>/dev/null || true)"
  case "$prior_state" in
    running|startup|starting)
      write_run_status failed "" "VM rebooted before benchmark completed"
      ;;
    "")
      write_run_status idle
      ;;
  esac
}

BENCHMARK_USER="$(metadata_attr benchmark-user)"
SPELL_REPO_URL="$(metadata_attr spell-repo-url)"
SPELL_REF="$(metadata_attr spell-ref)"
BENCHMARKING_REPO_URL="$(metadata_attr benchmarking-repo-url)"
BENCHMARKING_REF="$(metadata_attr benchmarking-ref)"
ANTHROPIC_SECRET="$(metadata_attr anthropic-secret)"
OPENAI_SECRET="$(metadata_attr openai-secret)"
GITHUB_TOKEN_SECRET="$(metadata_attr github-token-secret)"
CODEX_AUTH_SECRET="$(metadata_attr codex-auth-secret || true)"
CC_OAUTH_SECRET="$(metadata_attr cc-oauth-secret || true)"
RUN_GROUP="$(metadata_attr run-group || true)"
BENCHMARK_COMMAND="$(metadata_attr benchmark-command || true)"
PROJECT_ID="$(project_id)"
USER_HOME="/home/${BENCHMARK_USER}"
BOOTSTRAP_MARKER="/var/lib/spell-benchmark/bootstrap-done"

if ! id "$BENCHMARK_USER" >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash "$BENCHMARK_USER"
fi

if [[ -f "$BOOTSTRAP_MARKER" ]] && [[ -d "$USER_HOME/spell/.git" ]] && [[ -d "$USER_HOME/spell/benchmarking/.git" ]]; then
  echo "[spell-benchmark] bootstrap already complete, taking fast path"
  materialize_secrets_and_env
  systemctl enable --now docker
  wait_for_docker
  ensure_tmux_session
  mark_interrupted_run_if_needed
  echo "$STARTUP_OK_MARKER"
  exit 0
fi

write_run_status startup

echo "[spell-benchmark] installing base packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq \
  ca-certificates \
  curl \
  docker.io \
  git \
  jq \
  openjdk-17-jdk \
  python3 \
  python3-venv \
  rlwrap \
  tmux \
  unzip >/dev/null

usermod -aG docker "$BENCHMARK_USER"
install_clojure_cli

systemctl enable --now docker
wait_for_docker

echo "[spell-benchmark] installing docker compose plugin"
mkdir -p /usr/local/lib/docker/cli-plugins
curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

materialize_secrets_and_env

echo "[spell-benchmark] installing uv, Python 3.13, Node, Codex, and Claude Code"
install_uv_and_python
install_node_tooling

echo "[spell-benchmark] cloning spell and spell-benchmarking"
rm -rf "$USER_HOME/spell"
clone_repo "$SPELL_REPO_URL" "$USER_HOME/spell" "$SPELL_REF" "$GITHUB_TOKEN"
mkdir -p "$USER_HOME/spell"
clone_repo "$BENCHMARKING_REPO_URL" "$USER_HOME/spell/benchmarking" "$BENCHMARKING_REF" "$GITHUB_TOKEN"
chown -R "$BENCHMARK_USER:$BENCHMARK_USER" "$USER_HOME/spell"
unset GITHUB_TOKEN

echo "[spell-benchmark] warming benchmark dependencies"
run_as_benchmark_user '
  set -euo pipefail
  source "$HOME/.bashrc"
  cd "$HOME/spell/benchmarking"
  uv sync --python 3.13
  cd "$HOME/spell"
  clojure -P
'

echo "[spell-benchmark] creating tmux session"
ensure_tmux_session

write_run_status idle
mkdir -p "$(dirname "$BOOTSTRAP_MARKER")"
touch "$BOOTSTRAP_MARKER"

echo "$STARTUP_OK_MARKER"
