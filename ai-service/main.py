from fastapi import FastAPI
from routers import summarize
from services.upload_limit import SummaryUploadLimit

app = FastAPI(title="AI Summary Service")
app.add_middleware(SummaryUploadLimit, max_bytes=summarize.MAX_PDF_BYTES + 64 * 1024)

app.include_router(summarize.router)

@app.get("/health")
def health_check():
    return {"status": "ok"}
