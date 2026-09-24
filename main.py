from datetime import datetime, timezone
from zoneinfo import ZoneInfo
import re
import os
import json
import hashlib
import asyncio
import httpx
import asyncpg
from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel, Field

app = FastAPI(title="Blackout UA API", version="0.10.0")

YASNO_ROOT = "https://app.yasno.ua/api/blackout-service/public/shutdowns"
YASNO_ADDRESS = f"{YASNO_ROOT}/addresses/v2"
YASNO_REGIONS = {
    "kyiv": {"region_id": 25, "dso_id": 902},
    "dnipro-dtek": {"region_id": 3, "dso_id": 301},
    "dnipro-cek": {"region_id": 3, "dso_id": 303},
}
STATUS_MAP = {
    "NoOutages": "ON",
    "WaitingForSchedule": "UNKNOWN",
    "ScheduleApplies": "SCHEDULED",
}
STREET_STOPWORDS = {"вул", "вулиця", "просп", "проспект", "пров", "провулок", "бул", "бульвар", "пл", "площа"}
DATABASE_URL = os.getenv("DATABASE_URL")
POLL_INTERVAL_SECONDS = max(60, int(os.getenv("POLL_INTERVAL_SECONDS", "300")))
poll_task = None
db_pool = None

@app.on_event("startup")
async def startup():
    global db_pool, poll_task
    if not DATABASE_URL:
        return
    db_pool = await asyncpg.create_pool(DATABASE_URL, min_size=1, max_size=3)
    async with db_pool.acquire() as conn:
        await conn.execute("""CREATE TABLE IF NOT EXISTS schedule_snapshots (
            id BIGSERIAL PRIMARY KEY,
            provider TEXT NOT NULL,
            region TEXT NOT NULL,
            group_name TEXT NOT NULL,
            provider_updated_at TEXT,
            content_hash TEXT NOT NULL,
            payload JSONB NOT NULL,
            fetched_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )""")
        await conn.execute("""CREATE INDEX IF NOT EXISTS idx_snapshots_lookup
            ON schedule_snapshots(provider, region, group_name, fetched_at DESC)""")
        await conn.execute("""CREATE TABLE IF NOT EXISTS change_events (
            id BIGSERIAL PRIMARY KEY,
            provider TEXT NOT NULL,
            region TEXT NOT NULL,
            group_name TEXT NOT NULL,
            snapshot_id BIGINT NOT NULL,
            event_type TEXT NOT NULL,
            payload JSONB NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
            delivered BOOLEAN NOT NULL DEFAULT FALSE
        )""")
        await conn.execute("""CREATE INDEX IF NOT EXISTS idx_change_events_lookup ON change_events(region, group_name, created_at DESC)""")
        await conn.execute("""CREATE TABLE IF NOT EXISTS devices (
            id BIGSERIAL PRIMARY KEY,
            installation_id TEXT UNIQUE NOT NULL,
            fcm_token TEXT UNIQUE,
            platform TEXT NOT NULL DEFAULT 'android',
            enabled BOOLEAN NOT NULL DEFAULT TRUE,
            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )""")
        await conn.execute("""CREATE TABLE IF NOT EXISTS device_subscriptions (
            device_id BIGINT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            provider TEXT NOT NULL,
            region TEXT NOT NULL,
            group_name TEXT NOT NULL,
            notify_changes BOOLEAN NOT NULL DEFAULT TRUE,
            notify_before_minutes INTEGER NOT NULL DEFAULT 30,
            PRIMARY KEY(device_id, provider, region, group_name)
        )""")
        await conn.execute("""CREATE INDEX IF NOT EXISTS idx_subscriptions_group ON device_subscriptions(provider,region,group_name)""")
        await conn.execute("""CREATE TABLE IF NOT EXISTS tracked_groups (
            provider TEXT NOT NULL,
            region TEXT NOT NULL,
            group_name TEXT NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
            PRIMARY KEY(provider, region, group_name)
        )""")
    poll_task = asyncio.create_task(poll_loop())

@app.on_event("shutdown")
async def shutdown():
    if poll_task:
        poll_task.cancel()
        try:
            await poll_task
        except asyncio.CancelledError:
            pass
    if db_pool:
        await db_pool.close()

class DeviceRegister(BaseModel):
    installation_id: str = Field(min_length=8, max_length=128)
    fcm_token: str | None = Field(default=None, min_length=20, max_length=4096)
    platform: str = Field(default="android", pattern="^(android|ios|web)$")

class SubscriptionRequest(BaseModel):
    installation_id: str = Field(min_length=8, max_length=128)
    provider: str = "yasno"
    region: str
    group: str
    notify_changes: bool = True
    notify_before_minutes: int = Field(default=30, ge=0, le=1440)

@app.get("/")
async def root():
    return {"name": "Blackout UA API", "version": "0.10.0", "docs": "/docs", "database": "connected" if db_pool else "disabled"}

@app.get("/health")
async def health():
    return {"status": "ok", "database": "connected" if db_pool else "disabled", "time": datetime.now(timezone.utc).isoformat()}

@app.get("/api/v1/regions")
async def regions():
    return {"regions": [{"id": k, "provider": "yasno", **v} for k, v in YASNO_REGIONS.items()]}

def minute_to_time(value: int) -> str:
    value = max(0, min(int(value), 1440))
    if value == 1440:
        return "24:00"
    return f"{value // 60:02d}:{value % 60:02d}"

def normalize_slots(slots) -> list:
    result = []
    for slot in slots or []:
        if not isinstance(slot, dict):
            continue
        start = slot.get("start")
        end = slot.get("end")
        slot_type = slot.get("type")
        if start is None or end is None:
            continue
        item = {
            "start": minute_to_time(start),
            "end": minute_to_time(end),
            "type": slot_type,
        }
        if slot_type == "Definite":
            item["status"] = "OFF"
        elif slot_type == "NotPlanned":
            item["status"] = "ON"
        else:
            item["status"] = "UNKNOWN"
        result.append(item)
    return result

def normalize_day(day: dict) -> dict:
    raw_status = day.get("status")
    date = day.get("date")
    slots = normalize_slots(day.get("slots"))
    return {
        "date": date[:10] if isinstance(date, str) else date,
        "status": STATUS_MAP.get(raw_status, raw_status or "UNKNOWN"),
        "slots": slots,
        "outages": [x for x in slots if x["status"] == "OFF"],
    }

async def yasno_get(path: str, params: dict | None = None):
    try:
        async with httpx.AsyncClient(timeout=15.0, follow_redirects=True) as client:
            response = await client.get(path, params=params)
            response.raise_for_status()
            return response.json()
    except httpx.HTTPStatusError as exc:
        raise HTTPException(status_code=502, detail=f"Provider HTTP {exc.response.status_code}") from exc
    except (httpx.RequestError, ValueError) as exc:
        raise HTTPException(status_code=502, detail=f"Provider request failed: {type(exc).__name__}") from exc

def street_queries(street: str) -> list[str]:
    original = " ".join(street.strip().split())
    cleaned = re.sub(r"[^0-9A-Za-zА-Яа-яІіЇїЄєҐґ'’ -]+", " ", original)
    words = [w for w in cleaned.split() if w.casefold().rstrip(".") not in STREET_STOPWORDS]
    candidates = [original]
    if words:
        candidates.append(" ".join(words))
        # YASNO is much more reliable with the distinctive surname/name fragment.
        candidates.extend(reversed(words))
        if len(words) >= 2:
            candidates.append(" ".join(reversed(words)))
    result = []
    seen = set()
    for q in candidates:
        q = q.strip()
        key = q.casefold()
        if len(q) >= 2 and key not in seen:
            seen.add(key)
            result.append(q)
    return result

async def resolve_address(region: str, street: str, house: str) -> dict:
    config = YASNO_REGIONS.get(region)
    if config is None:
        raise HTTPException(status_code=404, detail=f"Unsupported region: {region}")

    common = {"regionId": config["region_id"], "dsoId": config["dso_id"]}
    streets = []
    matched_query = None
    for query in street_queries(street):
        data = await yasno_get(f"{YASNO_ADDRESS}/streets", {**common, "query": query})
        if isinstance(data, list) and data:
            streets = data
            matched_query = query
            break

    if not streets:
        raise HTTPException(status_code=404, detail={"message": "Street not found", "tried": street_queries(street)})

    street_item = streets[0]
    street_id = street_item.get("id")
    if street_id is None:
        raise HTTPException(status_code=502, detail="Provider returned street without id")

    houses = await yasno_get(
        f"{YASNO_ADDRESS}/houses",
        {**common, "streetId": street_id, "query": house},
    )
    if not isinstance(houses, list) or not houses:
        raise HTTPException(status_code=404, detail="House not found")

    wanted = house.strip().casefold()
    exact = next(
        (x for x in houses if str(x.get("value", x.get("name", x.get("number", "")))).strip().casefold() == wanted),
        houses[0],
    )
    house_id = exact.get("id")
    if house_id is None:
        raise HTTPException(status_code=502, detail="Provider returned house without id")

    group_data = await yasno_get(
        f"{YASNO_ADDRESS}/group",
        {**common, "streetId": street_id, "houseId": house_id},
    )

    groups = []
    if isinstance(group_data, dict):
        if group_data.get("group") is not None and group_data.get("subgroup") is not None:
            groups.append(f'{group_data["group"]}.{group_data["subgroup"]}')
        raw_groups = group_data.get("groups")
        if isinstance(raw_groups, list):
            for item in raw_groups:
                if isinstance(item, str):
                    groups.append(item)
                elif isinstance(item, dict) and item.get("group") is not None and item.get("subgroup") is not None:
                    groups.append(f'{item["group"]}.{item["subgroup"]}')

    groups = list(dict.fromkeys(groups))
    if not groups:
        raise HTTPException(status_code=404, detail={"message": "Group not found for address", "provider_data": group_data})

    return {
        "region": region,
        "provider": "yasno",
        "street": street_item,
        "house": exact,
        "groups": groups,
        "matched_query": matched_query,
    }

@app.get("/api/v1/address/search")
async def address_search(
    region: str,
    street: str = Query(..., min_length=2),
    house: str = Query(..., min_length=1),
):
    return await resolve_address(region, street, house)

@app.get("/api/v1/address/{region}/{street}/{house}")
async def address_lookup(region: str, street: str, house: str):
    return await resolve_address(region, street, house)

def schedule_hash(schedule: dict) -> str:
    stable = {"provider_updated_at": schedule.get("provider_updated_at"), "today": schedule.get("today"), "tomorrow": schedule.get("tomorrow")}
    raw = json.dumps(stable, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode()).hexdigest()

def outage_diff(previous: dict, current: dict) -> list:
    changes = []
    for key in ("today", "tomorrow"):
        old_day, new_day = previous.get(key) or {}, current.get(key) or {}
        date = new_day.get("date") or old_day.get("date")
        old_outages = {(x.get("start"), x.get("end")) for x in old_day.get("outages") or []}
        new_outages = {(x.get("start"), x.get("end")) for x in new_day.get("outages") or []}
        if old_day.get("status") != new_day.get("status"):
            changes.append({"type": "DAY_STATUS_CHANGED", "date": date, "from": old_day.get("status"), "to": new_day.get("status")})
        for start, end in sorted(new_outages - old_outages):
            changes.append({"type": "OUTAGE_ADDED", "date": date, "start": start, "end": end})
        for start, end in sorted(old_outages - new_outages):
            changes.append({"type": "OUTAGE_REMOVED", "date": date, "start": start, "end": end})
    return changes

async def save_snapshot(schedule: dict) -> dict:
    if not db_pool:
        return {"database": "disabled", "saved": False, "changed": None}
    digest = schedule_hash(schedule)
    async with db_pool.acquire() as conn:
        previous = await conn.fetchrow(
            "SELECT id, content_hash, payload FROM schedule_snapshots WHERE provider=$1 AND region=$2 AND group_name=$3 ORDER BY fetched_at DESC LIMIT 1",
            schedule["provider"], schedule["region"], schedule["group"]
        )
        if previous and previous["content_hash"] == digest:
            return {"database": "connected", "saved": False, "changed": False, "snapshot_id": previous["id"], "changes": []}
        row = await conn.fetchrow(
            "INSERT INTO schedule_snapshots(provider,region,group_name,provider_updated_at,content_hash,payload) VALUES($1,$2,$3,$4,$5,$6::jsonb) RETURNING id",
            schedule["provider"], schedule["region"], schedule["group"], schedule.get("provider_updated_at"), digest, json.dumps(schedule, ensure_ascii=False)
        )
        changes = outage_diff(previous["payload"], schedule) if previous else []
        for change in changes:
            await conn.execute(
                "INSERT INTO change_events(provider,region,group_name,snapshot_id,event_type,payload) VALUES($1,$2,$3,$4,$5,$6::jsonb)",
                schedule["provider"], schedule["region"], schedule["group"], row["id"], change["type"], json.dumps(change, ensure_ascii=False)
            )
        return {"database": "connected", "saved": True, "changed": previous is not None, "snapshot_id": row["id"], "previous_snapshot_id": previous["id"] if previous else None, "changes": changes}

def time_to_minute(value: str) -> int:
    hour, minute = value.split(":")
    return int(hour) * 60 + int(minute)

def current_summary(schedule: dict) -> dict:
    now = datetime.now(ZoneInfo("Europe/Kyiv"))
    today = schedule.get("today") or {}
    tomorrow = schedule.get("tomorrow") or {}
    minute = now.hour * 60 + now.minute
    status = "UNKNOWN"
    until = None
    if today.get("date") == now.date().isoformat():
        if today.get("status") == "ON":
            status = "ON"
        elif today.get("status") == "UNKNOWN":
            status = "UNKNOWN"
        else:
            status = "ON"
            for slot in today.get("slots") or []:
                start, end = time_to_minute(slot["start"]), time_to_minute(slot["end"])
                if start <= minute < end:
                    status, until = slot["status"], slot["end"]
                    break
    candidates = []
    for day in (today, tomorrow):
        for outage in day.get("outages") or []:
            date = day.get("date")
            if not date:
                continue
            start_minute = time_to_minute(outage["start"])
            if date > now.date().isoformat() or (date == now.date().isoformat() and start_minute > minute):
                candidates.append({"date": date, "start": outage["start"], "end": outage["end"]})
    candidates.sort(key=lambda x: (x["date"], x["start"]))
    return {"status": status, "until": until, "checked_at": now.isoformat(), "next_outage": candidates[0] if candidates else None}


async def track_group(region: str, group: str):
    if not db_pool:
        return
    async with db_pool.acquire() as conn:
        await conn.execute(
            "INSERT INTO tracked_groups(provider,region,group_name) VALUES($1,$2,$3) ON CONFLICT DO NOTHING",
            "yasno", region, group
        )

async def poll_once() -> dict:
    if not db_pool:
        return {"checked": 0, "changed": 0}
    async with db_pool.acquire() as conn:
        rows = await conn.fetch("SELECT region, group_name FROM tracked_groups WHERE provider='yasno'")
    checked = changed = 0
    for row in rows:
        try:
            result = await get_outages(row["region"], row["group_name"], track=False)
            checked += 1
            if result.get("snapshot", {}).get("changed"):
                changed += 1
        except Exception:
            continue
    return {"checked": checked, "changed": changed}

async def poll_loop():
    while True:
        try:
            await poll_once()
        except Exception:
            pass
        await asyncio.sleep(POLL_INTERVAL_SECONDS)

async def get_outages(region: str, group: str, track: bool = True) -> dict:
    config = YASNO_REGIONS.get(region)
    if config is None:
        raise HTTPException(status_code=404, detail=f"Unsupported region: {region}")

    data = await yasno_get(
        f"{YASNO_ROOT}/regions/{config['region_id']}/dsos/{config['dso_id']}/planned-outages"
    )
    group_data = data.get(group)
    if group_data is None:
        raise HTTPException(status_code=404, detail={"message": "Group not found", "group": group})

    result = {
        "region": region,
        "group": group,
        "provider": "yasno",
        "fetched_at": datetime.now(timezone.utc).isoformat(),
        "provider_updated_at": group_data.get("updatedOn"),
        "today": normalize_day(group_data.get("today") or {}),
        "tomorrow": normalize_day(group_data.get("tomorrow") or {}),
    }
    result["current"] = current_summary(result)
    if track:
        await track_group(region, group)
    result["snapshot"] = await save_snapshot(result)
    return result

@app.get("/api/v1/outages/{region}/{group}")
async def outages(region: str, group: str):
    return await get_outages(region, group)

@app.get("/api/v1/address-outages")
async def address_outages(
    region: str,
    street: str = Query(..., min_length=2),
    house: str = Query(..., min_length=1),
):
    address = await resolve_address(region, street, house)
    schedules = [await get_outages(region, group) for group in address["groups"]]
    return {
        "region": region,
        "provider": "yasno",
        "address": {
            "street": address["street"],
            "house": address["house"],
            "matched_query": address["matched_query"],
        },
        "groups": address["groups"],
        "schedules": schedules,
    }


@app.get("/api/v1/address-outages/{region}/{street}/{house}")
async def address_outages_path(region: str, street: str, house: str):
    address = await resolve_address(region, street, house)
    schedules = [await get_outages(region, group) for group in address["groups"]]
    return {
        "region": region,
        "provider": "yasno",
        "address": {
            "street": address["street"],
            "house": address["house"],
            "matched_query": address["matched_query"],
        },
        "groups": address["groups"],
        "schedules": schedules,
    }


@app.get("/api/v1/history/{region}/{group}")
async def history(region: str, group: str, limit: int = Query(20, ge=1, le=100)):
    if not db_pool:
        raise HTTPException(status_code=503, detail="Database is not configured")
    async with db_pool.acquire() as conn:
        rows = await conn.fetch(
            "SELECT id, provider, provider_updated_at, payload, fetched_at FROM schedule_snapshots WHERE region=$1 AND group_name=$2 ORDER BY fetched_at DESC LIMIT $3",
            region, group, limit
        )
    return {"region": region, "group": group, "snapshots": [
        {"id": r["id"], "provider": r["provider"], "provider_updated_at": r["provider_updated_at"], "fetched_at": r["fetched_at"].isoformat(), "schedule": r["payload"]}
        for r in rows
    ]}


@app.get("/api/v1/monitor/status")
async def monitor_status():
    if not db_pool:
        raise HTTPException(status_code=503, detail="Database is not configured")
    async with db_pool.acquire() as conn:
        count = await conn.fetchval("SELECT COUNT(*) FROM tracked_groups")
    return {"running": poll_task is not None and not poll_task.done(), "interval_seconds": POLL_INTERVAL_SECONDS, "tracked_groups": count}


@app.get("/api/v1/events/{region}/{group}")
async def change_events(region: str, group: str, limit: int = Query(50, ge=1, le=200)):
    if not db_pool:
        raise HTTPException(status_code=503, detail="Database is not configured")
    async with db_pool.acquire() as conn:
        rows = await conn.fetch(
            "SELECT id, snapshot_id, event_type, payload, created_at, delivered FROM change_events WHERE region=$1 AND group_name=$2 ORDER BY created_at DESC LIMIT $3",
            region, group, limit
        )
    return {"region": region, "group": group, "events": [
        {"id": r["id"], "snapshot_id": r["snapshot_id"], "type": r["event_type"], "payload": r["payload"], "created_at": r["created_at"].isoformat(), "delivered": r["delivered"]}
        for r in rows
    ]}

@app.get("/api/v1/events/pending/count")
async def pending_events_count():
    if not db_pool:
        raise HTTPException(status_code=503, detail="Database is not configured")
    async with db_pool.acquire() as conn:
        count = await conn.fetchval("SELECT COUNT(*) FROM change_events WHERE delivered=FALSE")
    return {"pending": count}


@app.post("/api/v1/devices/register")
async def register_device(body: DeviceRegister):
    if not db_pool:
        raise HTTPException(status_code=503, detail="Database is not configured")
    async with db_pool.acquire() as conn:
        row = await conn.fetchrow(
            "INSERT INTO devices(installation_id,fcm_token,platform) VALUES($1,$2,$3) ON CONFLICT(installation_id) DO UPDATE SET fcm_token=EXCLUDED.fcm_token, platform=EXCLUDED.platform, enabled=TRUE, updated_at=NOW() RETURNING id,installation_id,platform,enabled",
            body.installation_id, body.fcm_token, body.platform
        )
    return dict(row)

@app.post("/api/v1/subscriptions")
async def subscribe(body: SubscriptionRequest):
    if not db_pool:
        raise HTTPException(status_code=503, detail="Database is not configured")
    if body.provider != "yasno" or body.region not in YASNO_REGIONS:
        raise HTTPException(status_code=400, detail="Unsupported provider or region")
    async with db_pool.acquire() as conn:
        device_id = await conn.fetchval("SELECT id FROM devices WHERE installation_id=$1 AND enabled=TRUE", body.installation_id)
        if not device_id:
            raise HTTPException(status_code=404, detail="Device is not registered")
        await conn.execute(
            "INSERT INTO device_subscriptions(device_id,provider,region,group_name,notify_changes,notify_before_minutes) VALUES($1,$2,$3,$4,$5,$6) ON CONFLICT(device_id,provider,region,group_name) DO UPDATE SET notify_changes=EXCLUDED.notify_changes, notify_before_minutes=EXCLUDED.notify_before_minutes",
            device_id, body.provider, body.region, body.group, body.notify_changes, body.notify_before_minutes
        )
    await track_group(body.region, body.group)
    return {"subscribed": True, "provider": body.provider, "region": body.region, "group": body.group, "notify_changes": body.notify_changes, "notify_before_minutes": body.notify_before_minutes}

@app.get("/api/v1/subscriptions/{installation_id}")
async def subscriptions(installation_id: str):
    if not db_pool:
        raise HTTPException(status_code=503, detail="Database is not configured")
    async with db_pool.acquire() as conn:
        rows = await conn.fetch("SELECT s.provider,s.region,s.group_name,s.notify_changes,s.notify_before_minutes FROM device_subscriptions s JOIN devices d ON d.id=s.device_id WHERE d.installation_id=$1 AND d.enabled=TRUE ORDER BY s.region,s.group_name", installation_id)
    return {"installation_id": installation_id, "subscriptions": [
        {"provider": r["provider"], "region": r["region"], "group": r["group_name"], "notify_changes": r["notify_changes"], "notify_before_minutes": r["notify_before_minutes"]} for r in rows
    ]}
