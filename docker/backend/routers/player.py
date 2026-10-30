"""
Player router — play, skip, session end.

CRITICAL FIX: The play() endpoint now downloads the CURRENT track,
not just the next 2. Previously position 0 (seed) was never downloaded.
Also adds a status guard to prevent duplicate concurrent downloads.
"""

import uuid
import threading
import os
from fastapi import APIRouter, HTTPException, Depends, Request
from pydantic import BaseModel
from sqlalchemy.orm import Session
from typing import Optional, List
from datetime import datetime

from backend.db.database import get_db, SessionLocal
from backend.db.models import Session as DBSession, QueueEntry, User
from backend.routers.auth import get_current_user
from backend.services import apple_service, cache_manager

router = APIRouter()


class PlayRequest(BaseModel):
    session_id: str
    position: int
    apple_url: Optional[str] = None
    title: Optional[str] = None
    artist: Optional[str] = None


class PlayResponse(BaseModel):
    stream_url: Optional[str]
    cache_status: str
    song_title: str
    artist: str
    entry_id: str
    bit_depth: Optional[int] = None
    sample_rate: Optional[int] = None
    bitrate: Optional[int] = None
    phase: Optional[str] = None
    progress: Optional[float] = None
    error_msg: Optional[str] = None


class SkipRequest(BaseModel):
    session_id: str
    position: int


class SessionEndRequest(BaseModel):
    session_id: str


class QueueStatusItem(BaseModel):
    position: int
    title: str
    artist: str
    status: str
    stream_url: Optional[str]
    id: str
    thumbnail_url: str
    video_id: str
    bit_depth: Optional[int] = None
    sample_rate: Optional[int] = None


class QueueInsertRequest(BaseModel):
    current_position: int
    title: str
    artist: str
    thumbnail_url: str
    video_id: str


class QueueReorderRequest(BaseModel):
    new_order_ids: List[str]


@router.post("/play", response_model=PlayResponse)
def play(req: PlayRequest, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    """
    Play a track:
    1. If already cached → return stream URL immediately.
    2. Check credentials → if missing, fail fast with informative error.
    3. If already downloading → return status (frontend polls).
    4. Otherwise → resolve Apple URL + start download, then precache next 2.
    """
    entry = db.query(QueueEntry).filter(
        QueueEntry.session_id == req.session_id,
        QueueEntry.position == req.position
    ).first()

    if not entry:
        raise HTTPException(status_code=404, detail="Queue entry not found")
        
    if req.apple_url:
        entry.apple_url = req.apple_url
        db.commit()

    # ── Check Global Disk/DB Cache First ──────────────────────────────────────
    if not entry.cache_path:
        search_filter = [QueueEntry.cache_path.isnot(None), QueueEntry.status.in_(["cached", "playing"])]
        candidates = []
        if entry.apple_url:
            candidates = db.query(QueueEntry).filter(QueueEntry.apple_url == entry.apple_url, *search_filter).all()
        if not candidates and entry.title:
            candidates = db.query(QueueEntry).filter(QueueEntry.title == entry.title, *search_filter).all()

        existing = next((c for c in candidates if c.cache_path and os.path.exists(c.cache_path) and c.cache_path.endswith('.flac')), None)
        if not existing:
            existing = next((c for c in candidates if c.cache_path and os.path.exists(c.cache_path)), None)

        if existing and existing.cache_path and os.path.exists(existing.cache_path):
            entry.cache_path = existing.cache_path
            entry.alac_path = existing.alac_path
            if not entry.apple_url:
                entry.apple_url = existing.apple_url
            entry.status = "cached"
            db.commit()
            print(f"[Player] Reused existing cache for '{entry.title}' -> {entry.cache_path}")

    # ── Case 1: Already cached and file exists ────────────────────────────────
    if entry.cache_path and os.path.exists(entry.cache_path):
        entry.status = "playing"
        db.commit()
        filename = os.path.basename(entry.cache_path)
        
        bit_depth = None
        sample_rate = None
        try:
            if entry.cache_path.endswith('.flac'):
                from mutagen.flac import FLAC
                audio = FLAC(entry.cache_path)
                bit_depth = audio.info.bits_per_sample
                sample_rate = audio.info.sample_rate
        except Exception:
            pass
            
        bitrate = None
        try:
            file_size = os.path.getsize(entry.cache_path)
            if audio.info.length > 0:
                bitrate = int((file_size * 8) / (audio.info.length * 1000))
        except Exception:
            pass

        _trigger_precache(req.session_id, req.position, db, current_user.id)
        return PlayResponse(
            stream_url=f"/api/stream/{filename}",
            cache_status="cached",
            song_title=entry.title,
            artist=entry.artist or "",
            entry_id=entry.id,
            bit_depth=bit_depth,
            sample_rate=sample_rate,
            bitrate=bitrate,
            phase="ready",
            progress=100.0
        )

    # ── Check Apple Music credentials ───────────────────────────────────────
    user = db.query(User).filter(User.id == current_user.id).first()
    from backend.services.apple_wrapper_manager import _probe_port
    has_apple = bool((user and user.apple_username and user.apple_password) or _probe_port("host.docker.internal", 10020))
    if not has_apple:
        entry.status = "error"
        entry.error_msg = "Apple Music credentials not configured. Please configure in the Configuration tab."
        db.commit()
        return PlayResponse(
            stream_url=None,
            cache_status="error",
            song_title=entry.title,
            artist=entry.artist or "",
            entry_id=entry.id,
            error_msg=entry.error_msg
        )

    from backend.services.download_manager import download_manager

    # ── Case 2: Already being downloaded — don't re-trigger ──────────────────
    if entry.status == "resolving":
        curr_phase, curr_prog = download_manager.get_task_phase(entry.id)
        return PlayResponse(
            stream_url=None,
            cache_status="resolving",
            song_title=entry.title,
            artist=entry.artist or "",
            entry_id=entry.id,
            phase=curr_phase,
            progress=curr_prog
        )

    # ── Case 3: Need to resolve Apple URL and download ─────────────────────────
    # Trigger precache which handles resolution and downloading for current + next 1
    _trigger_precache(req.session_id, req.position, db, current_user.id)
    curr_phase, curr_prog = download_manager.get_task_phase(entry.id)
    
    return PlayResponse(
        stream_url=None,
        cache_status="resolving",
        song_title=entry.title,
        artist=entry.artist or "",
        entry_id=entry.id,
        phase=curr_phase,
        progress=curr_prog
    )


@router.post("/skip")
def skip(req: SkipRequest, db: Session = Depends(get_db)):
    """Mark a track as skipped and delete its cache file immediately."""
    entry = db.query(QueueEntry).filter(
        QueueEntry.session_id == req.session_id,
        QueueEntry.position == req.position
    ).first()

    if entry:
        cache_path = entry.cache_path
        entry.status = "skipped"
        entry.cache_path = None
        db.commit()
        cache_manager.delete_cached_file(cache_path)

    return {"status": "skipped", "position": req.position}


@router.post("/session/end")
async def end_session(request: Request, db: Session = Depends(get_db)):
    """
    End a session and wipe all remaining cached files.
    Accepts both JSON (normal fetch) and plain-text (navigator.sendBeacon).
    """
    import json as _json
    session_id = None
    try:
        body = await request.body()
        if body:
            data = _json.loads(body.decode("utf-8"))
            session_id = data.get("session_id")
    except Exception:
        pass

    if not session_id:
        return {"status": "no_session_id"}

    cache_manager.wipe_session_cache(session_id, db)
    return {"status": "session_ended", "session_id": session_id}


@router.post("/session/{session_id}/queue/insert")
def insert_queue_item(session_id: str, req: QueueInsertRequest, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    """Insert an item immediately after the current position ('Play Next')."""
    
    # Check if the song at current_position + 1 is already the exact same song to prevent accidental double-clicks
    existing_next = db.query(QueueEntry).filter(
        QueueEntry.session_id == session_id,
        QueueEntry.position == req.current_position + 1
    ).first()
    
    if existing_next and existing_next.video_id == req.video_id:
        # Ignore duplicate click
        return {"status": "already_next", "id": existing_next.id, "position": existing_next.position}

    # Shift all items after current_position by +1
    items_to_shift = db.query(QueueEntry).filter(
        QueueEntry.session_id == session_id,
        QueueEntry.position > req.current_position
    ).all()
    
    for item in items_to_shift:
        item.position += 1
        
    # Insert new item
    new_entry = QueueEntry(
        id=str(uuid.uuid4()),
        session_id=session_id,
        position=req.current_position + 1,
        title=req.title,
        artist=req.artist,
        thumbnail=req.thumbnail_url,
        video_id=req.video_id,
        status="pending"
    )
    db.add(new_entry)
    db.commit()
    
    _trigger_precache(session_id, req.current_position, db, current_user.id)
    
    return {"status": "inserted", "id": new_entry.id, "position": new_entry.position}


@router.put("/session/{session_id}/queue/reorder")
def reorder_queue(session_id: str, req: QueueReorderRequest, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    """Update the exact position of all items in the queue based on ID array."""
    entries = db.query(QueueEntry).filter(QueueEntry.session_id == session_id).all()
    
    id_to_entry = {e.id: e for e in entries}
    
    for idx, entry_id in enumerate(req.new_order_ids):
        if entry_id in id_to_entry:
            id_to_entry[entry_id].position = idx
            
    db.commit()
    # Identify current active position
    playing_entry = db.query(QueueEntry).filter(
        QueueEntry.session_id == session_id,
        QueueEntry.status.in_(["playing", "resolving"])
    ).first()
    curr_pos = playing_entry.position if playing_entry else 0
    
    # Dynamically update download priority with new order
    _trigger_precache(session_id, curr_pos, db, current_user.id)

    return {"status": "reordered"}


@router.get("/queue/{session_id}", response_model=List[QueueStatusItem])
def get_queue(session_id: str, db: Session = Depends(get_db)):
    """Get current queue with status for all entries."""
    entries = db.query(QueueEntry).filter(
        QueueEntry.session_id == session_id
    ).order_by(QueueEntry.position).all()

    result = []
    for e in entries:
        stream_url = None
        bit_depth = None
        sample_rate = None
        if e.cache_path and os.path.exists(e.cache_path):
            stream_url = f"/api/stream/{os.path.basename(e.cache_path)}"
            try:
                if e.cache_path.endswith('.flac'):
                    from mutagen.flac import FLAC
                    audio = FLAC(e.cache_path)
                    bit_depth = audio.info.bits_per_sample
                    sample_rate = audio.info.sample_rate
            except Exception:
                pass
        result.append(QueueStatusItem(
            position=e.position,
            title=e.title,
            artist=e.artist or "",
            status=e.status,
            stream_url=stream_url,
            id=e.id,
            thumbnail_url=e.thumbnail or "",
            video_id=e.video_id or "",
            bit_depth=bit_depth,
            sample_rate=sample_rate
        ))
    return result


@router.get("/entry/{entry_id}")
def get_entry_status(entry_id: str, db: Session = Depends(get_db)):
    """Poll status of a single queue entry. Returns stream_url and audio quality when ready."""
    entry = db.query(QueueEntry).filter(QueueEntry.id == entry_id).first()
    if not entry:
        raise HTTPException(status_code=404, detail="Entry not found")

    stream_url = None
    bit_depth = None
    sample_rate = None
    bitrate = None

    if not entry.cache_path:
        search_filter = [QueueEntry.cache_path.isnot(None), QueueEntry.status.in_(["cached", "playing"])]
        candidates = []
        if entry.apple_url:
            candidates = db.query(QueueEntry).filter(QueueEntry.apple_url == entry.apple_url, *search_filter).all()
        if not candidates and entry.title:
            candidates = db.query(QueueEntry).filter(QueueEntry.title == entry.title, *search_filter).all()

        existing = next((c for c in candidates if c.cache_path and os.path.exists(c.cache_path) and c.cache_path.endswith('.flac')), None)
        if not existing:
            existing = next((c for c in candidates if c.cache_path and os.path.exists(c.cache_path)), None)

        if existing and existing.cache_path and os.path.exists(existing.cache_path):
            entry.cache_path = existing.cache_path
            entry.alac_path = existing.alac_path
            entry.status = "cached"
            db.commit()

    if entry.cache_path and os.path.exists(entry.cache_path):
        stream_url = f"/api/stream/{os.path.basename(entry.cache_path)}"
        # Update status to cached if file now exists
        if entry.status not in ("playing", "cached"):
            entry.status = "cached"
            
        # Extract audio quality
        try:
            if entry.cache_path.endswith('.flac'):
                from mutagen.flac import FLAC
                audio = FLAC(entry.cache_path)
                bit_depth = audio.info.bits_per_sample
                sample_rate = audio.info.sample_rate
                file_size = os.path.getsize(entry.cache_path)
                if audio.info.length > 0:
                    bitrate = int((file_size * 8) / (audio.info.length * 1000))
        except Exception as e:
            print(f"[Player] Failed to extract audio quality for {entry.cache_path}: {e}")

    from backend.services.download_manager import download_manager
    realtime_phase, realtime_prog = download_manager.get_task_phase(entry.id)
    if entry.status in ("cached", "playing") or stream_url is not None:
        realtime_phase = "ready"
        realtime_prog = 100.0

    return {
        "id": entry.id,
        "status": entry.status,
        "stream_url": stream_url,
        "apple_url": entry.apple_url,
        "error_msg": entry.error_msg,
        "bit_depth": bit_depth,
        "sample_rate": sample_rate,
        "bitrate": bitrate,
        "phase": realtime_phase,
        "progress": realtime_prog
    }


def _trigger_precache(session_id: str, current_position: int, db: Session, user_id: int):
    """Resolve Apple URLs and start downloading the current (if pending) and next 2 tracks."""
    user = db.query(User).filter(User.id == user_id).first()
    from backend.services.apple_wrapper_manager import _probe_port
    has_apple = bool((user and user.apple_username and user.apple_password) or _probe_port("host.docker.internal", 10020))

    # Strict window: only current track (diff = 0) and immediate next track (diff = 1)
    entries = db.query(QueueEntry).filter(
        QueueEntry.session_id == session_id,
        QueueEntry.position >= current_position,
        QueueEntry.position <= current_position + 1,
        QueueEntry.status.in_(["pending", "resolved", "resolving", "error"])
    ).order_by(QueueEntry.position).all()

    if not has_apple:
        for entry in entries:
            if entry.status not in ("cached", "playing"):
                entry.status = "error"
                entry.error_msg = "Apple Music credentials not configured. Please configure in the Configuration tab."
        db.commit()
        return

    to_cache = []
    for entry in entries:
        if not entry.apple_url:
            url = apple_service.resolve_apple_url(entry.title, entry.artist or "")
            entry.apple_url = url
            entry.status = "resolved" if url else "error"
            if not url:
                entry.error_msg = "Apple Music URL not found"
            db.commit()

        if entry.apple_url:
            entry.status = "resolving"
            entry.error_msg = None
            db.commit()
            to_cache.append({
                "id": entry.id,
                "position": entry.position,
                "apple_url": entry.apple_url,
                "song_title": entry.title,
                "artist": entry.artist
            })

    if to_cache:
        cache_manager.pre_cache_tracks(to_cache, session_id, user_id, current_position, SessionLocal)
