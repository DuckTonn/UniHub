"""Verify the public HTTP contract through Nginx and the gateway."""

import base64
import hashlib
import hmac
import json
import os
import sys
import time
from datetime import datetime
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
    if data is not None and not isinstance(data, bytes):
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
    body, _ = request(base, "/api/v1/admin/stats", 200, token=admin)
    stats = json.loads(body)
    datetime.fromisoformat(stats["dataAsOf"].replace("Z", "+00:00"))
    assert isinstance(stats["workshopFillRates"], list)
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
        staff_access = verify_client_login(base, "app", "app-login@example.test", "STAFF")
        for client, email in (("web", "app-login@example.test"), ("app", "web-login@example.test")):
            request(base, f"/api/v1/auth/login/{client}", 403, method="POST",
                    data={"email": email, "password": os.environ["IDENTITY_FIXTURE_PASSWORD"]})
        verify_outbox_registration(base, web_access, student, os.environ["OUTBOX_FIXTURE_WORKSHOP"])
        verify_payment_and_checkin(base, web_access, staff_access, student, os.environ["PAYMENT_FIXTURE_WORKSHOP"])
        verify_document_job(base, admin, web_access, os.environ["PAYMENT_FIXTURE_WORKSHOP"])

    for path in ("/actuator/health", "/summarize"):
        request(base, path, 404)
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


def verify_outbox_registration(base, token, competing_token, workshop_id):
    assert workshop_id.isdigit(), "Test workshop fixture is missing"
    workshop = f"/api/v1/workshops/{workshop_id}"
    request(base, f"{workshop}/register", 201, token=token, method="POST")
    request(base, f"{workshop}/register", 409, token=competing_token, method="POST")
    body, _ = request(base, workshop, 200, token=competing_token)
    assert json.loads(body)["remainingSlots"] == 0
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


def verify_payment_and_checkin(base, web_access, staff_access, another_student, workshop_id):
    assert workshop_id.isdigit(), "Paid workshop fixture is missing"
    workshop = f"/api/v1/workshops/{workshop_id}"
    pending, _ = request(base, f"{workshop}/register", 202, token=web_access, method="POST")
    key = json.loads(pending)["idempotencyKey"]
    headers = {"Idempotency-Key": key}
    request(base, f"{workshop}/pay", 404, token=another_student, method="POST", headers=headers)
    first, _ = request(base, f"{workshop}/pay", 200, token=web_access, method="POST", headers=headers)
    repeat, _ = request(base, f"{workshop}/pay", 200, token=web_access, method="POST", headers=headers)
    payment = json.loads(first)
    assert payment == json.loads(repeat)
    assert payment["success"] and payment["transactionId"] and payment["qrCode"].startswith("QR-")

    scanned_at = datetime.now().isoformat(timespec="seconds")
    batch = {"workshopId": int(workshop_id), "records": [
        {"qrCode": payment["qrCode"], "scannedAt": scanned_at},
        {"qrCode": "unknown-ticket", "scannedAt": scanned_at},
    ]}
    path = "/api/v1/checkins/sync"
    request(base, path, 400, token=web_access, method="POST", data=batch)
    for success, duplicate in ((1, 0), (0, 1)):
        body, _ = request(base, path, 200, token=staff_access, method="POST", data=batch)
        result = json.loads(body)
        assert result["totalReceived"] == 2 and result["failedCount"] == 1
        assert result["successCount"] == success and result["duplicateCount"] == duplicate
        assert result["failures"][0]["qrCode"] == "unknown-ticket"


def verify_document_job(base, admin, student, workshop_id):
    workshop = f"/api/v1/admin/workshops/{workshop_id}"
    path = f"{workshop}/ai-summary"
    request(base, path, 400, token=student)
    body, _ = request(base, path, 200, token=admin)
    assert json.loads(body)["status"] == "IDLE"

    boundary = "unihub-smoke-pdf"
    multipart = (
        f"--{boundary}\r\n"
        'Content-Disposition: form-data; name="file"; filename="workshop.pdf"\r\n'
        "Content-Type: application/pdf\r\n\r\n"
    ).encode() + fixture_pdf() + f"\r\n--{boundary}--\r\n".encode()
    request(base, path, 400, token=student, method="POST", data=multipart,
            headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    request(base, path, 202, token=admin, method="POST", data=multipart,
            headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})

    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        body, _ = request(base, path, 200, token=admin)
        job = json.loads(body)
        assert job["status"] != "FAILED", job["error"]
        if job["status"] == "COMPLETED":
            body, _ = request(base, workshop, 200, token=admin)
            assert json.loads(body)["description"].startswith("Workshop document fixture")
            return
        time.sleep(2)
    raise AssertionError("AI document job did not complete")


def fixture_pdf():
    text = b"BT /F1 12 Tf 20 100 Td (Workshop document fixture) Tj ET"
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] "
        b"/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        f"<< /Length {len(text)} >>\nstream\n".encode() + text + b"\nendstream",
    ]
    pdf = bytearray(b"%PDF-1.4\n")
    offsets = []
    for number, value in enumerate(objects, 1):
        offsets.append(len(pdf))
        pdf.extend(f"{number} 0 obj\n".encode() + value + b"\nendobj\n")
    xref = len(pdf)
    pdf.extend(b"xref\n0 6\n0000000000 65535 f \n")
    for offset in offsets:
        pdf.extend(f"{offset:010d} 00000 n \n".encode())
    pdf.extend(f"trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    return bytes(pdf)


if __name__ == "__main__":
    main(*sys.argv[1:])
