import logging
import os
from pathlib import Path
import subprocess
import sys

from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse

from services.llm_client import LLMClientError, generate_summary_and_speaker
from services.text_cleaner import clean_text

router = APIRouter()
logger = logging.getLogger(__name__)
MAX_PDF_BYTES = 10 * 1024 * 1024
EXTRACTOR = Path(__file__).resolve().parents[1] / "services" / "pdf_extractor.py"


@router.post("/summarize")
def summarize_pdf(workshop_id: int = Form(...), file: UploadFile = File(...)):
    contents = file.file.read(MAX_PDF_BYTES + 1)
    if len(contents) > MAX_PDF_BYTES:
        raise HTTPException(status_code=413, detail="PDF exceeds 10 MiB")
    try:
        text = ""
        if contents.startswith(b"%PDF-"):
            parsed = subprocess.run(
                [sys.executable, "-I", str(EXTRACTOR)], input=contents, stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL, timeout=8,
                # Parsing needs no provider credentials.
                env={"SYSTEMROOT": os.environ["SYSTEMROOT"]} if "SYSTEMROOT" in os.environ else {},
            )
            if parsed.returncode == 0:
                text = clean_text(parsed.stdout.decode("utf-8"))
        if not text.strip():
            return invalid_document()
        result = generate_summary_and_speaker(text)
        return {
            "workshop_id": workshop_id,
            "summary": result.get("summary", ""),
            "speaker": result.get("speaker"),
        }
    except subprocess.TimeoutExpired:
        return invalid_document()
    except LLMClientError as error:
        raise HTTPException(status_code=error.status_code, detail=error.detail) from None
    except Exception as error:
        logger.error("Document processing failed for workshop %s (%s)", workshop_id, type(error).__name__)
        raise HTTPException(status_code=500, detail="Internal server error") from None


def invalid_document():
    return JSONResponse(status_code=400, content={"error": "Could not extract text from PDF file."})
