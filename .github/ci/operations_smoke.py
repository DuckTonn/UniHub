"""Load and defensive HTTP assertions for the disposable Compose stack."""

from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import re
import statistics
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from gateway_smoke import jwt, request


def online(base):
    _, first = request(base, "/api/test", 200, headers={"X-Request-ID": "client-generated"})
    _, second = request(base, "/api/test", 200)
    assert re.fullmatch(r"[0-9a-f]{32}", first["X-Request-ID"])
    assert second["X-Request-ID"] != first["X-Request-ID"]
    assert first["X-Content-Type-Options"] == "nosniff"
    assert first["X-Frame-Options"] == "DENY"
    assert first["Referrer-Policy"] == "no-referrer"
    for path in ("/actuator/prometheus", "/metrics", "/health", "/summarize"):
        request(base, path, 404)
    _, rejected = request(base, "/api/v1/auth/me", 403, method="OPTIONS", headers={
        "Origin": "https://different.example",
        "Access-Control-Request-Method": "GET",
        "Access-Control-Request-Headers": "Authorization",
    })
    assert "Access-Control-Allow-Origin" not in rejected

    # Start after the preceding smoke traffic leaves the existing 20-per-10s window.
    time.sleep(11)
    with ThreadPoolExecutor(max_workers=8) as pool:
        latency = list(pool.map(lambda _: catalog_request(base), range(16)))
    p95 = statistics.quantiles(latency, n=100, method="inclusive")[94]
    assert p95 < 2.5, f"Catalog p95 exceeded 2.5 s: {p95:.3f}s"
    print(f"Catalog load: 16 responses, concurrency 8, p95 {p95:.3f}s")

    wait_for_metrics()
    print("Private targets and operational metrics are available.")


def catalog_request(base):
    start = time.monotonic()
    body, _ = request(base, "/api/v1/workshops?page=0&size=5", 200)
    assert len(json.loads(body)["content"]) <= 5
    return time.monotonic() - start


def prometheus(query):
    from urllib.parse import urlencode

    with urlopen("http://127.0.0.1:19090/api/v1/query?" + urlencode({"query": query}), timeout=5) as response:
        return json.load(response)["data"]["result"]


def wait_for_metrics():
    expected = {"ai", "backend", "gateway"}
    metrics = ("unihub_ai_jobs_active", "unihub_operations_last_refresh_seconds",
               "unihub_ai_summary_seconds_count", "spring_cloud_gateway_requests_seconds_count")
    # Targets may be up before the first scrape after a smoke request creates a metric series.
    deadline = time.monotonic() + 45
    while True:
        healthy = {sample["metric"]["job"] for sample in prometheus("up") if sample["value"][1] == "1"}
        missing = [metric for metric in metrics if not prometheus(metric)]
        if healthy == expected and not missing:
            return
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise AssertionError(f"Prometheus healthy targets: {sorted(healthy)}. Missing metrics: {missing}")
        time.sleep(min(2, remaining))


def recovered(base):
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        try:
            with urlopen(Request(base + "/api/test"), timeout=5) as response:
                if response.status == 200 and response.read() == b"Server is running":
                    print("API recovered after Redis returned.")
                    return
        except (HTTPError, URLError, TimeoutError):
            pass
        time.sleep(1)
    raise AssertionError("API did not recover after Redis returned")


def production(base, workshop, student, env_file):
    key = next(line.split("=", 1)[1] for line in Path(env_file).read_text().splitlines()
               if line.startswith("JWT_ACCESS_SECRET="))
    token = jwt(key, student, "STUDENT")
    request(base, "/api/v1/payments/process", 403, method="POST", data={"amount": 1})
    request(base, "/api/v1/payment-webhooks/stripe", 403, method="POST", data={})
    path = f"/api/v1/workshops/{int(workshop)}"
    body, _ = request(base, f"{path}/register", 202, token=token, method="POST")
    request(base, f"{path}/pay", 503, token=token, method="POST",
            headers={"Idempotency-Key": json.loads(body)["idempotencyKey"]})
    print("Production refused a paid charge without a configured provider.")


def main(base, scenario, *parameters):
    if scenario == "online":
        online(base)
    elif scenario == "redis-down":
        body, headers = request(base, "/api/test", 503)
        assert json.loads(body)["status"] == 503 and headers["Retry-After"] == "3"
        print("Redis outage returned controlled HTTP 503.")
    elif scenario == "recovered":
        recovered(base)
    elif scenario == "production":
        production(base, *parameters)
    else:
        raise ValueError("Unknown operations scenario")


if __name__ == "__main__":
    main(*sys.argv[1:])
