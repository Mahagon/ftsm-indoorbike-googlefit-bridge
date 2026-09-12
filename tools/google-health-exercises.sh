#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: google-health-exercises.sh --start DATE --end DATE [--package-name NAME]

Reads Google Health exercises in the half-open civil-date interval [DATE, DATE)
and returns records imported from Health Connect for the selected Android package.

Required environment variable:
  GOOGLE_HEALTH_ACCESS_TOKEN  OAuth token with the
                              googlehealth.activity_and_fitness.readonly scope
EOF
}

start=""
end=""
package_name="dev.frakw.ftmsbridge"

while (($# > 0)); do
  case "$1" in
    --start)
      [[ $# -ge 2 ]] || { echo "Missing value for --start" >&2; usage >&2; exit 2; }
      start="$2"
      shift 2
      ;;
    --end)
      [[ $# -ge 2 ]] || { echo "Missing value for --end" >&2; usage >&2; exit 2; }
      end="$2"
      shift 2
      ;;
    --package-name)
      [[ $# -ge 2 ]] || { echo "Missing value for --package-name" >&2; usage >&2; exit 2; }
      package_name="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$start" || -z "$end" ]]; then
  echo "Both --start and --end are required." >&2
  usage >&2
  exit 2
fi

for command_name in curl jq; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "Required command not found: $command_name" >&2
    exit 1
  }
done

access_token="${GOOGLE_HEALTH_ACCESS_TOKEN:-}"
if [[ -z "$access_token" ]]; then
  echo "Set GOOGLE_HEALTH_ACCESS_TOKEN for a user authorized with googlehealth.activity_and_fitness.readonly." >&2
  exit 1
fi

filter="exercise.interval.civil_start_time >= \"$start\" AND exercise.interval.civil_start_time < \"$end\""
base_url="https://health.googleapis.com/v4/users/me/dataTypes/exercise/dataPoints"
data_points='[]'
page_token=""

while :; do
  curl_args=(
    --fail-with-body
    --silent
    --show-error
    --get
    --header "Authorization: Bearer $access_token"
    --data-urlencode "pageSize=25"
    --data-urlencode "filter=$filter"
  )
  if [[ -n "$page_token" ]]; then
    curl_args+=(--data-urlencode "pageToken=$page_token")
  fi

  response="$(curl "${curl_args[@]}" "$base_url")"
  data_points="$(
    jq --compact-output \
      --argjson existing "$data_points" \
      '$existing + (.dataPoints // [])' <<<"$response"
  )"
  page_token="$(jq --raw-output '.nextPageToken // empty' <<<"$response")"
  [[ -n "$page_token" ]] || break
done

jq \
  --arg package_name "$package_name" \
  '[
    .[]
    | select(
        .dataSource.platform == "HEALTH_CONNECT"
        and .dataSource.application.packageName == $package_name
      )
    | {name, dataSource, exercise}
  ]' <<<"$data_points"
