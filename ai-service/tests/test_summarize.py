import asyncio
from io import BytesIO
from types import SimpleNamespace
from threading import Event
from unittest import IsolatedAsyncioTestCase
from unittest.mock import patch

import httpx
from pypdf import PdfWriter
from pypdf.generic import DecodedStreamObject, DictionaryObject, NameObject

from main import app
from routers.summarize import MAX_PDF_BYTES
from services.llm_client import LLMClientError, generate_summary_and_speaker


def document():
    pdf = PdfWriter()
    page = pdf.add_blank_page(width=200, height=200)
    page[NameObject("/Resources")] = DictionaryObject({
        NameObject("/Font"): DictionaryObject({
            NameObject("/F1"): DictionaryObject({
                NameObject("/Type"): NameObject("/Font"),
                NameObject("/Subtype"): NameObject("/Type1"),
                NameObject("/BaseFont"): NameObject("/Helvetica"),
            }),
        }),
    })
    content = DecodedStreamObject()
    content.set_data(b"BT /F1 12 Tf 20 100 Td (Workshop document) Tj ET")
    page.replace_contents(content)
    output = BytesIO()
    pdf.write(output)
    return output.getvalue()


class SummaryContractTest(IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.http = httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")
        self.addAsyncCleanup(self.http.aclose)

    async def summarize(self, content):
        return await self.http.post("/summarize", data={"workshop_id": 17},
                                    files={"file": ("workshop.pdf", content, "application/pdf")})

    async def test_multipart_contract_and_nullable_speaker(self):
        with patch("routers.summarize.generate_summary_and_speaker",
                   return_value={"summary": "Example summary", "speaker": None}) as model:
            response = await self.summarize(document())
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"workshop_id": 17, "summary": "Example summary", "speaker": None})
        model.assert_called_once_with("Workshop document")

    async def test_malformed_pdf_has_existing_error_shape(self):
        with patch("routers.summarize.generate_summary_and_speaker") as model:
            response = await self.summarize(b"not a PDF")
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json(), {"error": "Could not extract text from PDF file."})
        model.assert_not_called()

    async def test_large_file_is_rejected_before_model_call(self):
        with patch("routers.summarize.generate_summary_and_speaker") as model:
            response = await self.summarize(b"x" * (MAX_PDF_BYTES + 1))
        self.assertEqual(response.status_code, 413)
        model.assert_not_called()

    async def test_chunked_body_cannot_bypass_request_limit(self):
        async def chunks():
            for _ in range(12):
                yield b"x" * (1024 * 1024)

        response = await self.http.post("/summarize", content=chunks())
        self.assertEqual(response.status_code, 413)

    async def test_missing_fields_and_provider_failures_preserve_status_codes(self):
        self.assertEqual((await self.http.post("/summarize")).status_code, 422)
        with patch("routers.summarize.generate_summary_and_speaker",
                   side_effect=LLMClientError(404, "AI model was not found")):
            response = await self.summarize(document())
        self.assertEqual(response.status_code, 404)
        self.assertEqual(response.json(), {"detail": "AI model was not found"})

    async def test_busy_workers_reject_extra_uploads_and_keep_health_available(self):
        release = Event()
        calls = asyncio.Queue()
        loop = asyncio.get_running_loop()

        def slow_model(_text):
            loop.call_soon_threadsafe(calls.put_nowait, True)
            if not release.wait(timeout=8):
                raise TimeoutError("Test did not release workers")
            return {"summary": "Done", "speaker": None}

        with patch("routers.summarize.generate_summary_and_speaker", side_effect=slow_model):
            workers = [asyncio.create_task(self.summarize(document())) for _ in range(2)]
            try:
                for _ in workers:
                    await asyncio.wait_for(calls.get(), timeout=5)
                self.assertEqual((await self.http.get("/health")).json(), {"status": "ok"})
                response = await self.summarize(document())
                self.assertEqual(response.status_code, 503)
                self.assertEqual(response.headers["retry-after"], "1")
            finally:
                release.set()
                await asyncio.gather(*workers)

    def test_gemini_adapter_passes_bounded_options_and_extracts_fields(self):
        with patch.dict("os.environ", {"LLM_PROVIDER": "gemini", "LLM_API_KEY": "test-placeholder"}), \
                patch("services.llm_client.genai.Client") as create_client:
            client = create_client.return_value.__enter__.return_value
            client.models.generate_content.return_value = SimpleNamespace(
                text='{"summary": "Workshop summary", "speaker": "Example speaker"}')
            result = generate_summary_and_speaker("Document text")

        self.assertEqual(result, {"summary": "Workshop summary", "speaker": "Example speaker"})
        options = create_client.call_args.kwargs["http_options"]
        self.assertEqual(options.timeout, 7000)
        self.assertEqual(options.retry_options.attempts, 1)
        request = client.models.generate_content.call_args.kwargs
        self.assertEqual(request["contents"], "Document text")
        self.assertEqual(request["config"]["response_mime_type"], "application/json")
