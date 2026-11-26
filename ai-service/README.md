# UniHub Workshop - AI Service

This FastAPI service extracts text from workshop PDFs and generates summaries with Gemini. The UniHub backend queues documents and calls this service over the Compose network.

## Features
- **PDF Text Extraction**: Extracts raw text content from uploaded PDF files.
- **Text Cleaning**: Cleans and normalizes text for better LLM processing.
- **AI Summarization**: Uses Google Gemini API to generate concise, professional workshop descriptions.

## Technology Stack
- **Framework**: FastAPI (Python)
- **PDF Processing**: pypdf
- **LLM Integration**: `google-genai` (Gemini)
- **Deployment**: Docker & Docker Compose

## Prerequisites
- Python 3.11+ (if running locally)
- Docker & Docker Compose (recommended)
- A Gemini API key for provider calls. Use `LLM_PROVIDER=mock` for local tests.

## Environment Variables
Set these variables in the shell for a local run or in the Compose environment file:

```env
LLM_PROVIDER=gemini
LLM_API_KEY=your_gemini_api_key_here
LLM_MODEL=gemini-3.1-flash-lite
LLM_API_VERSION=v1beta
```

If the configured model returns 404, the service looks for a compatible model on the first provider results page and tries it once. Choose a model available for the configured API key and version.

## Running the Service

### Option 1: Using Docker (Recommended)
You can build and run this service along with the rest of the application using the root `docker-compose.yml` file:

```bash
cd ../ # Go to project root
docker-compose up --build ai-service
```
The backend reaches the service at `http://ai-service:8000` on the Compose network.

### Option 2: Running Locally (Without Docker)
Navigate to the `ai-service` directory and install the dependencies:

```bash
cd ai-service
pip install -r requirements.txt
```

Run the FastAPI server using Uvicorn:

```bash
uvicorn main:app --host 127.0.0.1 --port 8000 --reload
```

## API Endpoints

### 1. `POST /summarize`
Receives a PDF file and a Workshop ID, processes it, and returns the generated summary.

**Request Form Data:**
- `workshop_id` (integer): The ID of the workshop.
- `file` (file): The PDF document to summarize.

**Success Response (200 OK):**
```json
{
  "workshop_id": 1,
  "summary": "This is the generated summary describing the workshop...",
  "speaker": null
}
```

**Error Responses:**
- `400 Bad Request`: If the PDF cannot be read or text extraction fails.
- `404 Not Found`: If the configured model is not available for your API key/API version.
- `413 Payload Too Large`: If the PDF exceeds 10 MiB.
- `503 Service Unavailable`: If both document slots are busy.
- `500 Internal Server Error`: For unexpected server errors.

Run the contract tests with `python -m pip install -r requirements-test.txt` and `python -m unittest discover -s tests -v`.
