"""Authenticated, bounded incident ingress; only the trusted runner can confirm a fix."""
import asyncio
import logging
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse

logger = logging.getLogger(__name__)


def build_router(identity_async, body, healer):
    router = APIRouter()

    def is_error(error, name):
        kind = getattr(type(healer), name, None)
        return isinstance(kind, type) and issubclass(kind, Exception) and isinstance(error, kind)

    @router.get('/mobile/healing/capabilities')
    async def capabilities(request: Request):
        await identity_async(request)
        return JSONResponse({'supported': True}, headers={'Cache-Control': 'no-store'})

    @router.post('/mobile/incidents')
    async def record(request: Request):
        owner, provider, profile, _ = await identity_async(request)
        payload = await body(request, limit=4096)
        if not isinstance(payload, dict) or set(payload) != {'incident_id', 'incident'} or not isinstance(payload['incident_id'], str):
            raise HTTPException(400, 'Invalid incident')
        try:
            incident = await asyncio.to_thread(healer.record, (owner, provider, profile), payload['incident'], event_id=payload['incident_id'])
        except Exception as error:
            if is_error(error, 'InvalidIncident'):
                raise HTTPException(400, 'Invalid incident') from None
            if is_error(error, 'IncidentConflict'):
                logger.warning('incident_identity_conflict')
                raise HTTPException(409, 'Incident identity conflict') from None
            if is_error(error, 'IncidentCapacity'):
                logger.warning('incident_capacity_reached')
                raise HTTPException(429, 'Incident capacity reached') from None
            logger.warning('incident_record_unavailable')
            raise HTTPException(503, 'Incident storage unavailable') from None
        # A durable receipt is the acknowledgement; recovery failure never asks the phone to upload it again.
        try: await asyncio.to_thread(healer.recover, (owner, provider, profile), incident['id'])
        except Exception: logger.warning('incident_recovery_deferred')
        return JSONResponse({'incident_id': payload['incident_id'], 'recorded': True}, headers={'Cache-Control': 'no-store'})

    @router.get('/mobile/incidents')
    async def incidents(request: Request):
        owner, provider, profile, _ = await identity_async(request)
        try: rows = await asyncio.to_thread(healer.list, (owner, provider, profile))
        except Exception:
            logger.warning('incident_list_unavailable')
            raise HTTPException(503, 'Incident storage unavailable') from None
        return JSONResponse({'incidents': rows}, headers={'Cache-Control': 'no-store'})

    return router
