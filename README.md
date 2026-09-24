# Blackout UA API — prototype

Provider-based backend prototype for normalizing Ukrainian electricity outage schedules.

## Run

```bash
python -m venv .venv
source .venv/bin/activate   # Windows: .venv\\Scripts\\activate
pip install -r requirements.txt
uvicorn app.main:app --reload
```

Open `/docs` for Swagger.

### Endpoints
- `GET /health`
- `GET /api/v1/regions`
- `GET /api/v1/outages/{region}/{group}`

Initial YASNO mappings: `kyiv`, `dnipro-dtek`, `dnipro-cek`.

DTEK is intentionally isolated behind a provider adapter because its regional sites use frontend WordPress AJAX and can be protected by Imperva/WAF. Next step: integrate a WAF-aware DTEK client server-side and add persistence/version diffing.
