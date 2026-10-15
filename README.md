# AKZSA Server — Render deployment (Phase 1)

FastAPI backend foundation for the AKZSA Android voice keyboard. This project is configured to deploy on **Render** using `render.yaml`.

## What this phase includes

- `GET /health` — readiness and configuration status
- `GET /` — service information
- `POST /v1/commands/interpret` — asks Groq to interpret a transcript and returns a structured action proposal
- Bearer-token guard for the command endpoint
- Request validation, timeouts, and safe error messages

The API proposes actions; it never performs edits on WhatsApp or other apps. The Android keyboard must validate and execute supported edits through its input connection.

## Deploy to Render

1. Extract this ZIP.
2. Create a new private GitHub repository, for example `akzsa-server`, and upload the **contents** of this extracted folder to the repository root. Ensure `render.yaml`, `requirements.txt`, and the `app/` folder are at the repository root (not nested inside another `akzsa-server/` folder).
3. In Render, choose **New + → Blueprint** (or create a Web Service and enter the same settings manually), then connect the GitHub repository.
4. Render reads `render.yaml` and creates the web service. When prompted for secret values, enter:
   - `GROQ_API_KEY`: your Groq API key
   - `AKZSA_SERVER_TOKEN`: a long, randomly generated token for initial testing
5. Deploy and wait until Render reports the service as live.
6. Open the service URL, such as `https://YOUR-SERVICE.onrender.com/health`. It should return JSON containing `"status": "ok"`.
7. Open `https://YOUR-SERVICE.onrender.com/docs` to test the API. For `POST /v1/commands/interpret`, supply `Authorization: Bearer YOUR_AKZSA_SERVER_TOKEN`.

Render's configured start command uses `0.0.0.0` and Render's `$PORT`, and `/health` is configured as the health-check path.

## Environment variables

- `GROQ_API_KEY` — required for command interpretation; set in Render's Environment page or during Blueprint setup. Never commit it to GitHub.
- `GROQ_MODEL` — defaults to `openai/gpt-oss-20b` in this project.
- `AKZSA_SERVER_TOKEN` — required when `AKZSA_ENV=production`; use a long random value for initial API testing.
- `AKZSA_ENV` — set to `production` by `render.yaml`.
- `LOG_LEVEL` — set to `INFO` by `render.yaml`.

## Example request

```json
{
  "transcript": "delete the last 10 words",
  "selected_text": null,
  "surrounding_text": null,
  "mode": "auto"
}
```

Expected response shape (the natural-language explanation can vary):

```json
{
  "action": {
    "kind": "delete_last_words",
    "count": 10,
    "text": null,
    "query": null,
    "language": null,
    "requires_confirmation": false,
    "explanation": "Delete the last 10 words"
  },
  "model": "openai/gpt-oss-20b",
  "server_executed_action": false,
  "message": "Action interpreted only; the client must validate and execute it."
}
```

## Important security note before public beta

The token guard is suitable for initial private testing, but a permanent shared token must **not** be embedded in a public Android APK: app packages can be inspected. Before public release, replace this with user/device authentication (short-lived tokens), rate limits, and appropriate abuse monitoring. Until then, keep the server in private development/testing and do not distribute the token.

## Not implemented yet

- Live streaming speech recognition
- Android keyboard frontend and direct live text insertion
- File picker UI, authorized file indexing, and ranked file search
- Real keyboard action execution

These are later phases, after this Render deployment and Groq command interpretation are verified.
