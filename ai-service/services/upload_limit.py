import asyncio

from starlette.datastructures import Headers
from starlette.requests import ClientDisconnect
from starlette.responses import JSONResponse


class SummaryUploadLimit:
    """Apply backpressure before multipart parsing, including for chunked uploads."""

    def __init__(self, app, max_bytes, max_concurrent=2):
        self.app = app
        self.max_bytes = max_bytes
        self.max_concurrent = max_concurrent
        self.active = 0

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope["method"] != "POST" or scope["path"].rstrip("/") != "/summarize":
            return await self.app(scope, receive, send)

        length = Headers(scope=scope).get("content-length", "")
        if length.isdecimal() and int(length) > self.max_bytes:
            return await self.reject(scope, receive, send, 413, "Upload exceeds size limit")
        if self.active >= self.max_concurrent:
            return await self.reject(scope, receive, send, 503, "Document processor is busy")

        self.active += 1
        try:
            try:
                body = await asyncio.wait_for(self.read_body(receive), timeout=15)
            except ClientDisconnect:
                return
            except TimeoutError:
                return await self.reject(scope, receive, send, 408, "Upload timed out")
            if body is None:
                return await self.reject(scope, receive, send, 413, "Upload exceeds size limit")

            async def replay():
                nonlocal body
                if body is None:
                    return await receive()
                message = {"type": "http.request", "body": bytes(body), "more_body": False}
                body = None
                return message

            return await self.app(scope, replay, send)
        finally:
            self.active -= 1

    async def read_body(self, receive):
        body = bytearray()
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                raise ClientDisconnect()
            chunk = message.get("body", b"")
            if len(body) + len(chunk) > self.max_bytes:
                return None
            body.extend(chunk)
            if not message.get("more_body", False):
                return body

    async def reject(self, scope, receive, send, status, detail):
        headers = {"Retry-After": "1"} if status == 503 else None
        return await JSONResponse({"detail": detail}, status_code=status, headers=headers)(scope, receive, send)
