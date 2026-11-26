#!/usr/bin/env bash
set -euo pipefail
export MSYS_NO_PATHCONV=1
export PYTHONDONTWRITEBYTECODE=1
export COMPOSE_PROFILES=messaging
export COMPOSE_IGNORE_ORPHANS=true
: "${UNIHUB_RELEASE_DRILL:?Set a disposable release output directory}"
: "${COMPOSE_PROJECT_NAME:?Set a disposable Compose project}"

registry="$COMPOSE_PROJECT_NAME-registry"
trap 'docker rm -f "$registry" >/dev/null 2>&1 || true' EXIT
docker run -d --rm --name "$registry" -p 127.0.0.1::5000 registry:3 >/dev/null
address="$(docker port "$registry" 5000/tcp)"
mkdir -p "$UNIHUB_RELEASE_DRILL"
old="$UNIHUB_RELEASE_DRILL/baseline.json"
new="$UNIHUB_RELEASE_DRILL/candidate.json"
commit="$(git rev-parse HEAD)"

compose() {
    docker compose --env-file "$UNIHUB_ENV_FILE" -f docker-compose.yml -f .github/ci/compose.yml "$@"
}

probe="$COMPOSE_PROJECT_NAME-prod-guard"
trap 'docker rm -f "$registry" "$probe" >/dev/null 2>&1 || true' EXIT
compose run -d --name "$probe" --no-deps -e SPRING_PROFILES_ACTIVE=prod backend >/dev/null
for attempt in {1..25}; do
    if [[ "$(docker inspect --format '{{.State.Status}}' "$probe")" == exited ]]; then break; fi
    sleep 2
done
[[ "$(docker inspect --format '{{.State.ExitCode}}' "$probe")" != 0 ]]
docker logs "$probe" 2>&1 | grep -F 'Disable seeded demo users before serving production traffic' >/dev/null
echo "Production refused to start with active demo identities."

compose exec -T postgres psql -U unihub -d unihub < ops/disable-demo-users.sql
password="$(python -c 'import secrets; print(secrets.token_hex(24))')"
compose exec -T postgres psql -U unihub -d unihub -v ON_ERROR_STOP=1 -v "password=$password" <<'SQL'
INSERT INTO users (full_name, email, password, role, status)
VALUES ('Rehearsal Admin', 'operator@example.test', crypt(:'password', gen_salt('bf')), 'ADMIN', 'ACTIVE');
SQL
unset password

bash .github/ci/push_images.sh "$address/unihub" baseline "$commit" "$old"
frontend="$(bash .github/ci/stack.sh image frontend)"
docker tag "$frontend" "$COMPOSE_PROJECT_NAME-frontend:drill-base"
printf 'FROM %s\nLABEL unihub.release-drill=candidate\n' "$COMPOSE_PROJECT_NAME-frontend:drill-base" |
    docker build -t "$COMPOSE_PROJECT_NAME-frontend-candidate" -
bash .github/ci/push_images.sh "$address/unihub" candidate "$commit" "$new" "$COMPOSE_PROJECT_NAME-frontend-candidate"

apply() {
    python ops/release.py --project "$COMPOSE_PROJECT_NAME" --env-file "$UNIHUB_ENV_FILE" \
        --manifest "$1" --monitoring --url http://127.0.0.1:18080
    local active expected
    expected="$(python -c 'import json,sys; print(json.load(open(sys.argv[1]))["images"]["frontend"])' "$1")"
    active="$(docker inspect --format '{{.Config.Image}}' "$COMPOSE_PROJECT_NAME-frontend-1")"
    [[ "$active" == "$expected" ]]
}

apply "$new"
workshop="$(compose exec -T postgres psql -U unihub -d unihub -qAtc "
    INSERT INTO workshops (title, status, total_slots, remaining_slots, price, room_id, start_time, end_time,
        registration_start_time, registration_end_time)
    VALUES ('Production payment availability fixture', 'PUBLISHED', 1, 1, 10000, (SELECT min(id) FROM rooms),
        now() + interval '1 day', now() + interval '2 days', now() - interval '1 hour', now() + interval '1 hour')
    RETURNING id")"
student="$(compose exec -T postgres psql -U unihub -d unihub -Atc "SELECT id FROM users WHERE email = 'web-login@example.test'")"
python .github/ci/operations_smoke.py http://127.0.0.1:18080 production "$workshop" "$student" "$UNIHUB_ENV_FILE"
compose exec -T postgres psql -U unihub -d unihub -Atc "
    SELECT p.status FROM payments p JOIN registrations r ON p.registration_id = r.id
    WHERE r.workshop_id = $workshop" | grep -Fx PENDING
apply "$old"
echo "Candidate deployed and baseline restored by immutable image references."
