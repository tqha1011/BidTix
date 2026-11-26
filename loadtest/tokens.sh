#!/usr/bin/env bash
# Makes a throwaway RSA key pair and one access token per load-test customer, signed the way JwtUtil signs them.
# Logging 5,000 users in would measure BCrypt, not the endpoints under test, and a real token lasts only 5 minutes.
#   loadtest/tokens.sh <output dir> <users>      writes <dir>/keys/{private,public}.pem and <dir>/tokens.json
set -euo pipefail

out=${1:?usage: tokens.sh <output dir> <users>}
users=${2:?usage: tokens.sh <output dir> <users>}
mkdir -p "$out/keys"
key="$out/keys/private.pem"

openssl genpkey -quiet -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$key"
openssl pkey -in "$key" -pubout -out "$out/keys/public.pem"
# the application runs as another user inside its container and has to read them; they sign nothing outside this test
chmod 644 "$out"/keys/*.pem

b64url() { basenc --base64url -w0 | tr -d '='; }

header=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
issued=$(date +%s)
expires=$((issued + 7200))

one_token() {
    local sub payload signature
    printf -v sub '00000000-0000-4000-8000-%012d' "$1"
    payload=$(printf '{"iss":"bidtix","aud":["bidtix-api"],"sub":"%s","iat":%d,"exp":%d,"roles":["CUSTOMER"]}' \
        "$sub" "$issued" "$expires" | b64url)
    signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -sign "$key" -binary | b64url)
    printf '"%s.%s.%s"\n' "$header" "$payload" "$signature"
}
export -f one_token b64url
export header issued expires key

# signed in parallel; xargs -P keeps the order of no particular user, which does not matter
seq "$users" \
    | xargs -P "$(nproc)" -n 100 bash -c 'for i in "$@"; do one_token "$i"; done' _ \
    | jq -cs . > "$out/tokens.json"

count=$(jq length "$out/tokens.json")
[[ $count -eq $users ]] || { echo "tokens.sh: made $count tokens, wanted $users" >&2; exit 1; }
