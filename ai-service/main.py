from time import monotonic

from fastapi import FastAPI, Request, Response
from prometheus_client import CONTENT_TYPE_LATEST, Histogram, generate_latest
from routers import summarize
from services.upload_limit import SummaryUploadLimit

app = FastAPI(title="AI Summary Service")
app.add_middleware(SummaryUploadLimit, max_bytes=summarize.MAX_PDF_BYTES + 64 * 1024)

app.include_router(summarize.router)

summary_duration = Histogram("unihub_ai_summary_seconds", "Time spent processing summary requests", ["status"])


@app.middleware("http")
async def measure_summary(request: Request, call_next):
    if request.method != "POST" or request.url.path.rstrip("/") != "/summarize":
        return await call_next(request)
    start = monotonic()
    status = 500
    try:
        response = await call_next(request)
        status = response.status_code
        return response
    finally:
        summary_duration.labels(status=f"{status // 100}xx").observe(monotonic() - start)


@app.get("/metrics", include_in_schema=False)
def metrics():
    return Response(content=generate_latest(), headers={"Content-Type": CONTENT_TYPE_LATEST})


@app.get("/health")
def health_check():
    return {"status": "ok"}
