#!/usr/bin/env bash
set -euo pipefail
export MSYS_NO_PATHCONV=1

repository="${1:?Registry repository required}"
tag="${2:?Image tag required}"
commit="${3:?Git commit required}"
output="${4:?Manifest output required}"
frontend_image="${5:-}"

references=()
for service in backend gateway ai-service frontend; do
    image="$(bash .github/ci/stack.sh image "$service")"
    if [[ "$service" == frontend && -n "$frontend_image" ]]; then image="$frontend_image"; fi
    [[ -n "$image" ]]
    name="$repository/$service"
    docker tag "$image" "$name:$tag"
    docker push "$name:$tag"
    digest="$(docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$name:$tag" |
        awk -v prefix="$name@" 'index($0, prefix) == 1 {print; exit}')"
    [[ -n "$digest" ]]
    references+=("$service=$digest")
done

python - "$commit" "$output" "${references[@]}" <<'PY'
import json
from pathlib import Path
import sys

commit, output, *references = sys.argv[1:]
images = dict(reference.split("=", 1) for reference in references)
Path(output).write_text(json.dumps({"commit": commit, "images": images}, indent=2) + "\n", encoding="utf-8")
PY
