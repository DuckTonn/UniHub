"""Verify the public HTTP contract through Nginx and the gateway."""

import base64
import hashlib
import hmac
import json
import os
import sys
import time
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def jwt(key, user_id, role, lifetime=300):
    def base64url(data):
        return base64.urlsafe_b64encode(data).rstrip(b"=")

    header = base64url(b'{"alg":"HS256","typ":"JWT"}')
    claims = base64url(json.dumps({
        "sub": user_id,
        "role": role,
        "exp": int(time.time()) + lifetime,
    }).encode())
    message = b".".join((header, claims))
    signature = base64url(hmac.new(key.encode(), message, hashlib.sha256).digest())
    return b".".join((message, signature)).decode()


def request(base, path, expected_status, *, token=None, method="GET", data=None, headers=None):
    headers = dict(headers or {})
    if token is not None:
        headers["Authorization"] = f"Bearer {token}"
    if data is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(data).encode()
    req = Request(base + path, data=data, headers=headers, method=method)
    try:
        response = urlopen(req, timeout=15)
    except HTTPError as error:
        response = error
    with response:
        body = response.read()
        if response.status != expected_status:
            message = f"{method} {path}: expected {expected_status}, got {response.status}"
            if not path.startswith("/api/v1/auth/"):
                message += f": {body[:500].decode(errors='replace')}"
            raise AssertionError(message)
        return body, response.headers


def main(base, env_file, student_id, admin_id):
    keys = dict(line.split("=", 1) for line in Path(env_file).read_text().splitlines()
                if line.startswith(("JWT_ACCESS_SECRET=", "JWT_REFRESH_SECRET=")))
    access_secret = keys["JWT_ACCESS_SECRET"]
    refresh_secret = keys["JWT_REFRESH_SECRET"]
    student = jwt(access_secret, student_id, "STUDENT")
    expired = jwt(access_secret, student_id, "STUDENT", lifetime=-60)
    refresh = jwt(refresh_secret, student_id, "STUDENT")
    admin = jwt(access_secret, admin_id, "ADMIN")

    body, _ = request(base, "/api/test", 200)
    assert body == b"Server is running"
    body, _ = request(base, "/api/v1/workshops?page=0&size=1", 200)
    assert len(json.loads(body)["content"]) <= 1

    request(base, "/api/v1/admin/stats", 403)
    # The backend's existing exception handler maps method-level access denial to 400.
    body, _ = request(base, "/api/v1/admin/stats", 400, token=student)
    assert json.loads(body)["message"] == "Access Denied"
    request(base, "/api/v1/admin/stats", 200, token=admin)
    body, _ = request(base, "/api/v1/admin/stats", 401, token="invalid")
    assert json.loads(body)["error"] == "Invalid token"
    body, _ = request(base, "/api/v1/admin/stats", 401, token=expired)
    assert json.loads(body)["error"] == "Token expired"
    request(base, "/api/v1/admin/stats", 401, token=refresh)

    origin = "https://client.example"
    _, headers = request(base, "/api/v1/auth/me", 200, method="OPTIONS", headers={
        "Origin": origin,
        "Access-Control-Request-Method": "GET",
        "Access-Control-Request-Headers": "Authorization",
    })
    assert headers["Access-Control-Allow-Origin"] == origin
    body, _ = request(base, "/api/v1/auth/refresh", 200, method="POST",
                      data={"refreshToken": refresh})
    new_access_token = json.loads(body)["accessToken"]
    _, headers = request(base, "/api/v1/auth/me", 200, token=new_access_token, headers={"Origin": origin})
    assert headers.get_all("Access-Control-Allow-Origin") == [origin]

    if "IDENTITY_FIXTURE_PASSWORD" in os.environ:
        web_access = verify_client_login(base, "web", "web-login@example.test", "STUDENT")
        verify_client_login(base, "app", "app-login@example.test", "STAFF")
        for client, email in (("web", "app-login@example.test"), ("app", "web-login@example.test")):
            request(base, f"/api/v1/auth/login/{client}", 403, method="POST",
                    data={"email": email, "password": os.environ["IDENTITY_FIXTURE_PASSWORD"]})
        verify_outbox_registration(base, web_access, os.environ["OUTBOX_FIXTURE_WORKSHOP"])

    for path in ("/actuator/health", "/summarize"):
        _, headers = request(base, path, 200)
        assert headers.get_content_type() == "text/html", f"{path} should serve the SPA"
    print("Gateway routing, CORS and backend JWT contracts passed.")


def verify_client_login(base, client, email, role):
    body, _ = request(base, f"/api/v1/auth/login/{client}", 200, method="POST",
                      data={"email": email, "password": os.environ["IDENTITY_FIXTURE_PASSWORD"]})
    login = json.loads(body)
    assert login["role"] == role
    body, _ = request(base, "/api/v1/auth/me", 200, token=login["accessToken"])
    assert json.loads(body)["userId"] == login["userId"]

    body, _ = request(base, "/api/v1/auth/refresh", 200, method="POST",
                      data={"refreshToken": login["refreshToken"]})
    refreshed = json.loads(body)
    assert refreshed["role"] == role and refreshed["userId"] == login["userId"]
    request(base, "/api/v1/notifications", 200, token=refreshed["accessToken"])
    return refreshed["accessToken"]


def verify_outbox_registration(base, token, workshop_id):
    assert workshop_id.isdigit(), "Test workshop fixture is missing"
    request(base, f"/api/v1/workshops/{workshop_id}/register", 201, token=token, method="POST")
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        body, _ = request(base, "/api/v1/notifications", 200, token=token)
        notifications = [item for item in json.loads(body)["content"]
                         if "Phase 2 outbox smoke fixture" in item["contentHtml"]]
        if notifications:
            assert len(notifications) == 1
            assert notifications[0]["channel"] == "IN_APP" and notifications[0]["status"] == "SUCCESS"
            return
        time.sleep(3)
    raise AssertionError("No registration notification arrived from the outbox")


if __name__ == "__main__":
    main(*sys.argv[1:])
