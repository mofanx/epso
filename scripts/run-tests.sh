#!/usr/bin/env bash
# Epso 自动化测试脚本
# 用法：
#   ./scripts/run-tests.sh
# 环境变量：
#   EPSO_HOST   默认 http://127.0.0.1:8888
#   EPSO_TOKEN  API token（本机 localhost 可省略）

set -euo pipefail

HOST="${EPSO_HOST:-http://127.0.0.1:8888}"
TOKEN="${EPSO_TOKEN:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

curl_extra=()
if [[ -n "$TOKEN" ]]; then
  curl_extra=(-H "Authorization: Bearer $TOKEN")
fi

echo "==> uploading test-suite.yml"
curl -s -X PUT "${HOST}/api/v1/files/test-suite" \
  "${curl_extra[@]}" \
  -H "Content-Type: text/plain" \
  --data-binary @"${SCRIPT_DIR}/test-suite.yml"
echo

echo "==> reload"
curl -s -X POST "${HOST}/api/v1/reload" "${curl_extra[@]}"
echo

run_case() {
  local name="$1" text="$2" expected="$3" pkg="${4:-}"

  local req
  req=$(jq -n --arg text "$text" --arg pkg "$pkg" '
    {
      text: $text,
      packageName: (if $pkg == "" then null else $pkg end)
    } | with_entries(select(.value != null))
  ')

  local resp_file
  resp_file=$(mktemp)
  curl -s -X POST "${HOST}/api/v1/expand" \
    "${curl_extra[@]}" \
    -H "Content-Type: application/json" \
    -d "$req" \
    -o "$resp_file"

  echo "  [$name] $req -> $(cat "$resp_file")"

  python3 /dev/stdin "$resp_file" "$name" "$expected" <<'PY'
import json, re, sys

resp_file, name, expected_json = sys.argv[1], sys.argv[2], sys.argv[3]
resp = json.load(open(resp_file))
exp = json.loads(expected_json)

ok = True
for k, v in exp.items():
    if k == 'replacement_regex':
        if not re.search(v, resp.get('replacement', ''), re.DOTALL):
            print(f'FAIL {name}: replacement {resp.get("replacement")!r} does not match regex {v!r}')
            ok = False
    elif resp.get(k) != v:
        print(f'FAIL {name}: {k} expected {v!r} got {resp.get(k)!r}')
        ok = False

print(f'PASS {name}' if ok else f'FAIL {name}')
sys.exit(0 if ok else 1)
PY
}

echo "==> running expansion tests"

run_case 'basic'          'h:'          '{"matched":true,"replacement":"hello, Alice","format":"text"}'
run_case 'date'           'd:'          '{"matched":true,"replacement_regex":"^today is [0-9]{4}-[0-9]{2}-[0-9]{2}$","format":"text"}'
run_case 'clipboard'      'clip:'       '{"matched":true,"replacement_regex":"^clipboard=.*","format":"text"}'
run_case 'random'         'rand:'       '{"matched":true,"replacement_regex":"^num=(100|[1-9][0-9]?)$","format":"text"}'
run_case 'markdown'       'md:'         '{"matched":true,"replacement_regex":"(?s).*Title.*bold.*code.*","format":"markdown"}'
run_case 'html'           'html:'       '{"matched":true,"replacement_regex":"(?s).*<b>bold</b>.*<i>italic</i>.*","format":"html"}'
run_case 'word_boundary'  'hello w: world' '{"matched":true,"replacement":"word","format":"text"}'
run_case 'left_boundary'  'hello lw:world' '{"matched":true,"replacement":"left","format":"text"}'
run_case 'prop_lower'     'prop:'       '{"matched":true,"replacement":"upper","format":"text"}'
run_case 'prop_upper'     'PROP:'       '{"matched":true,"replacement":"UPPER","format":"text"}'
run_case 'prop_cap'       'Prop:'       '{"matched":true,"replacement":"Upper","format":"text"}'
run_case 'regex'          ':date 2026-07-18' '{"matched":true,"replacement":"date matched","format":"text"}'
run_case 'image'          'img:'        '{"matched":true,"format":"image","imagePath":"%CONFIG%/test.png"}'
run_case 'form'           'form:'       '{"matched":true,"format":"form","needsInteraction":true}'
run_case 'filter_no_match' 'filter:'    '{"matched":false,"message":"filtered by app context"}'
run_case 'filter_match'   'filter:'     '{"matched":true,"replacement":"filtered","format":"text"}' 'com.example.notes'
run_case 'no_match'       'xyz:'        '{"matched":false,"message":"no match"}'

echo "==> all tests passed"
