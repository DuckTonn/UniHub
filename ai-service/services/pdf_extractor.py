import io
import sys

MAX_PAGES = 100
MAX_TEXT_CHARS = 40_000

def extract_text_from_pdf(pdf_file: io.BytesIO) -> str:
    from pypdf import PdfReader

    reader = PdfReader(pdf_file)
    if reader.is_encrypted or len(reader.pages) > MAX_PAGES:
        raise ValueError("Unsupported PDF")
    pages = []
    remaining = MAX_TEXT_CHARS
    for page in reader.pages:
        text = (page.extract_text() or "").strip()
        if text:
            part = text[:remaining]
            pages.append(part)
            remaining -= len(part) + 1
            if remaining <= 0:
                break
    return "\n".join(pages)


if __name__ == "__main__":
    try:
        if sys.platform == "linux":
            import resource

            memory = 256 * 1024 * 1024
            resource.setrlimit(resource.RLIMIT_AS, (memory, memory))
        result = extract_text_from_pdf(io.BytesIO(sys.stdin.buffer.read()))
        sys.stdout.buffer.write(result.encode("utf-8", errors="replace"))
    except Exception:
        sys.exit(1)
