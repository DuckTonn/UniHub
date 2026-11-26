"""Validate or apply an immutable UniHub release manifest on a Docker host."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
IMAGE_KEYS = {
    "backend": "UNIHUB_BACKEND_IMAGE",
    "gateway": "UNIHUB_GATEWAY_IMAGE",
    "ai-service": "UNIHUB_AI_IMAGE",
    "frontend": "UNIHUB_FRONTEND_IMAGE",
}
IMAGE = re.compile(r"[a-z0-9.-]+(?::[0-9]+)?(?:/[a-z0-9._-]+)+@sha256:[a-f0-9]{64}")


def read_manifest(path):
    manifest = json.loads(path.read_text(encoding="utf-8"))
    commit = manifest.get("commit", "")
    images = manifest.get("images", {})
    if not isinstance(commit, str) or not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("Manifest must identify a full Git commit")
    if not isinstance(images, dict) or images.keys() != IMAGE_KEYS.keys():
        raise ValueError("Manifest must list exactly the four UniHub application images")
    for image in images.values():
        if not isinstance(image, str) or not IMAGE.fullmatch(image):
            raise ValueError("Every image reference must include a registry path and SHA-256 digest")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--env-file", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--url", help="Base URL to verify after applying the release")
    parser.add_argument("--monitoring", action="store_true")
    parser.add_argument("--check", action="store_true", help="Validate files without pulling or starting containers")
    args = parser.parse_args()
    if not args.check and not args.url:
        parser.error("--url is required when applying a release")
    if not args.env_file.is_file():
        parser.error("--env-file does not exist")

    try:
        manifest = read_manifest(args.manifest)
    except (OSError, ValueError, AttributeError) as error:
        parser.error(str(error))
    env_file = args.env_file.resolve()
    env = os.environ | {"UNIHUB_ENV_FILE": str(env_file)}
    env.update({key: manifest["images"][service] for service, key in IMAGE_KEYS.items()})
    compose = ["docker", "compose", "--project-name", args.project, "--env-file", str(env_file),
               "-f", "docker-compose.yml", "-f", "ops/compose.release.yml"]
    if args.monitoring:
        compose += ["-f", "ops/compose.monitoring.yml"]

    def run(*arguments):
        subprocess.run([*compose, *arguments], cwd=ROOT, env=env, check=True)

    run("config", "--quiet")
    if args.check:
        print(f"Validated release {manifest['commit']}")
        return

    run("pull", "--policy", "missing")
    run("up", "-d", "--no-build", "--wait", "--wait-timeout", "180")
    with urlopen(args.url.rstrip("/") + "/api/test", timeout=10) as response:
        if response.status != 200 or response.read(128) != b"Server is running":
            raise RuntimeError("Release API smoke check failed")
    print(f"Applied release {manifest['commit']}")


if __name__ == "__main__":
    main()
