#!/usr/bin/env bash
#
# Creates (or updates) the Kubernetes Secret this bridge reads its credentials from, using
# the daikin.config.json you already have for running it locally.
#
#   ./deploy/k8s/create-secret.sh
#   ./deploy/k8s/create-secret.sh --config ~/daikin.config.json --namespace daikin-home
#   ./deploy/k8s/create-secret.sh --dry-run        # list the keys, set nothing
#
# Idempotent: run it again after editing the config and it updates the Secret in place.
# The bridge reads the Secret once at startup, so follow an update with:
#
#   kubectl -n daikin-home rollout restart deploy/daikin-home
#
# Secrets are passed to kubectl in a mode-600 temporary file rather than as
# --from-literal arguments, because command-line arguments are visible to any user on the
# machine through ps. The file is removed on exit, including on error.
set -euo pipefail

CONFIG="daikin.config.json"
NAMESPACE="daikin-home"
NAME="daikin-home-credentials"
DRY_RUN=false

usage() {
  cat <<'USAGE'
usage: create-secret.sh [options]

  --config <path>      configuration file to read (default: daikin.config.json)
  --namespace <name>   namespace to create the Secret in (default: daikin-home)
  --name <name>        Secret name (default: daikin-home-credentials)
  --dry-run            print the keys that would be set, and exit
  --help               show this message

Settings the Deployment supplies itself - dataDirectory, storagePath and bindAddress - are
skipped on purpose: it points dataDirectory at the mounted volume and takes bindAddress from
the node's own address, and a value here would override those with something wrong for the
cluster.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --config)    CONFIG="${2:?--config needs a path}"; shift 2 ;;
    --namespace) NAMESPACE="${2:?--namespace needs a name}"; shift 2 ;;
    --name)      NAME="${2:?--name needs a name}"; shift 2 ;;
    --dry-run)   DRY_RUN=true; shift ;;
    --help|-h)   usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

command -v python3 >/dev/null 2>&1 || {
  echo "error: python3 is required to read $CONFIG" >&2
  exit 1
}
$DRY_RUN || command -v kubectl >/dev/null 2>&1 || {
  echo "error: kubectl is not on PATH" >&2
  exit 1
}
[[ -r "$CONFIG" ]] || {
  echo "error: cannot read $CONFIG" >&2
  echo "Copy daikin.config.example.json and fill in your Daikin credentials, or pass --config." >&2
  exit 1
}

ENV_FILE="$(mktemp "${TMPDIR:-/tmp}/daikin-secret.XXXXXX")"
chmod 600 "$ENV_FILE"
trap 'rm -f "$ENV_FILE"' EXIT INT TERM

# Translates the config file into KEY=VALUE lines. The key names are the environment
# variables Spring binds to daikin.* settings, so anything valid in the config file works
# here too without this script needing to be updated for each new setting.
python3 - "$CONFIG" "$ENV_FILE" <<'PYTHON'
import json, re, sys

config_path, env_path = sys.argv[1], sys.argv[2]

try:
    with open(config_path, encoding="utf-8") as handle:
        config = json.load(handle)
except json.JSONDecodeError as error:
    sys.exit(f"error: {config_path} is not valid JSON: {error}")

if not isinstance(config, dict):
    sys.exit(f"error: {config_path} should contain a JSON object")

# The Deployment sets these itself, correctly for the cluster; a value from a local config
# file would point the bridge at a path or an interface that does not exist in the pod.
SKIP = {"dataDirectory", "storagePath", "bindAddress"}

# The only name that is not a straight camelCase-to-SNAKE_CASE translation: the config
# file calls it "port", but the service distinguishes it from the HTTP port.
RENAME = {"port": "DAIKIN_HOMEKIT_PORT"}

SECRET_KEYS = {"password", "apiKey", "integratorToken"}


def to_env(name):
    if name in RENAME:
        return RENAME[name]
    return "DAIKIN_" + re.sub(r"(?<!^)(?=[A-Z])", "_", name).upper()


lines, shown, skipped = [], [], []

for key, value in config.items():
    # Keys beginning with an underscore are the example file's own commentary.
    if key.startswith("_"):
        continue
    if key in SKIP:
        skipped.append(key)
        continue
    if value is None or (isinstance(value, str) and not value.strip()):
        continue

    if isinstance(value, bool):
        rendered = "true" if value else "false"
    elif isinstance(value, (int, float, str)):
        rendered = str(value).strip() if isinstance(value, str) else str(value)
    else:
        # Lists and nested objects have no environment-variable spelling; the config file
        # is the right home for those.
        skipped.append(key)
        continue

    if "\n" in rendered or "\r" in rendered:
        sys.exit(f'error: the value of "{key}" spans multiple lines, which a Secret built '
                 "from an env file cannot represent")

    env_name = to_env(key)
    lines.append(f"{env_name}={rendered}")
    shown.append(env_name + ("  (secret)" if key in SECRET_KEYS else ""))

# Mirrors the bridge's own validation, so a half-filled config fails here with the same
# explanation rather than in a CrashLoopBackOff.
names = {line.split("=", 1)[0] for line in lines}
if "DAIKIN_EMAIL" not in names:
    sys.exit("error: email is required")
has_openapi = {"DAIKIN_API_KEY", "DAIKIN_INTEGRATOR_TOKEN"} <= names
has_skyport = "DAIKIN_PASSWORD" in names
if not has_openapi and not has_skyport:
    sys.exit("error: supply either password (skyport backend) or both apiKey and "
             "integratorToken (openapi backend)")

with open(env_path, "w", encoding="utf-8") as handle:
    handle.write("\n".join(lines) + "\n")

print("keys read from " + config_path + ":")
for name in shown:
    print("  " + name)
if skipped:
    print("skipped (the Deployment supplies these): " + ", ".join(sorted(set(skipped))))
PYTHON

if $DRY_RUN; then
  echo
  echo "--dry-run: nothing was sent to the cluster."
  exit 0
fi

echo
# --dry-run=client plus apply, rather than a bare create, so re-running updates the Secret
# instead of failing with AlreadyExists. The YAML goes through a pipe, never the terminal.
kubectl create secret generic "$NAME" \
  --namespace "$NAMESPACE" \
  --from-env-file="$ENV_FILE" \
  --dry-run=client -o yaml \
  | kubectl apply -f -

echo
echo "The bridge reads this once at startup. If it is already running:"
echo "  kubectl -n $NAMESPACE rollout restart deploy/daikin-home"
