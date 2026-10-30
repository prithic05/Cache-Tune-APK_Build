"""
Search router — POST /api/search
Calls yt-music → creates session → stores queue → resolves Apple URLs for top 2.
"""

import uuid
import threading
from fastapi import APIRouter, HTTPException, Depends
from backend.routers.auth import get_current_user
from backend.db.database import get_db
from backend.db.models import User
from pydantic import BaseModel
from sqlalchemy.orm import Session
from typing import List, Optional, Any
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from backend.db.database import get_db, SessionLocal
from backend.db.models import Session as DBSession, QueueEntry
from backend.services import ytmusic_service, apple_service

router = APIRouter()


class SearchResult(BaseModel):
    videoId: str
    title: str
    artist: str
    thumbnail: str


class SessionStartRequest(BaseModel):
    video_id: Optional[str] = None
    song: Optional[Any] = None
    apple_url: Optional[str] = None
    title: Optional[str] = None
    artist: Optional[str] = None
    thumbnail_url: Optional[str] = None
    limit: int = 25


class QueueItem(BaseModel):
    position: int
    title: str
    artist: str
    id: str
    thumbnail_url: str
    video_id: str


class SessionResponse(BaseModel):
    session_id: str
    seed_title: str
    seed_artist: str
    seed_thumbnail: str
    queue: List[QueueItem]


@router.get("/search", response_model=List[SearchResult])
def search(q: str, limit: int = 15, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    """Standard search (does not create a session)."""
    try:
        results = ytmusic_service.search(db, current_user.id, q, limit=limit)
        return results
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"YT Music error: {str(e)}")


@router.post("/session/start", response_model=SessionResponse)
def start_session(req: SessionStartRequest, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    """Generate an autoplay queue from a seed and start a session."""
    # Handle dict/object song from mobile clients
    song_query = req.song
    if isinstance(req.song, dict):
        req_title = req.song.get("title", "")
        req_artist = req.song.get("artist", "")
        if req_title:
            song_query = f"{req_title} {req_artist}".strip()
            
    try:
        seed_title, seed_artist, seed_thumbnail, queue_items = ytmusic_service.search_and_queue(
            db, current_user.id, video_id=req.video_id, song=song_query, limit=req.limit
        )
    except Exception as e:
        # Fallback for mobile direct requests: use provided title/artist
        if isinstance(req.song, dict) and req.song.get("title"):
            seed_title = req.song.get("title")
            seed_artist = req.song.get("artist", "")
            seed_thumbnail = req.song.get("thumbnail_url", "")
            queue_items = []
        elif req.title:
            seed_title = req.title
            seed_artist = req.artist or ""
            seed_thumbnail = req.thumbnail_url or ""
            queue_items = []
        else:
            raise HTTPException(status_code=500, detail=f"YT Music error: {str(e)}")

    # Fallback to client-provided thumbnail if seed_thumbnail is blank
    if not seed_thumbnail:
        if isinstance(req.song, dict) and req.song.get("thumbnail_url"):
            seed_thumbnail = req.song.get("thumbnail_url")
        elif req.thumbnail_url:
            seed_thumbnail = req.thumbnail_url
        elif req.video_id:
            seed_thumbnail = f"https://i.ytimg.com/vi/{req.video_id}/hqdefault.jpg"

    # Create new session ID
    session_id = str(uuid.uuid4())

    # Build queue — seed is position 0, rest follow
    seed_track = {
        "title": seed_title, 
        "artist": seed_artist, 
        "videoId": req.video_id or (queue_items[0].get("videoId", "") if queue_items else ""), 
        "thumbnail": seed_thumbnail
    }
    all_tracks = [seed_track] + queue_items
    
    db_entries = []
    for i, track in enumerate(all_tracks):
        entry = QueueEntry(
            id=str(uuid.uuid4()),
            session_id=session_id,
            position=i,
            title=track["title"],
            artist=track.get("artist", "Unknown Artist"),
            thumbnail=track.get("thumbnail", ""),
            video_id=track.get("videoId", ""),
            status="pending"
        )
        db.add(entry)
        db_entries.append(entry)

    db.commit()

    # Resolve Apple Music URLs for top 2 queue items (positions 1 & 2) in background
    def _resolve_urls():
        for entry in db_entries[1:3]:  # positions 1 and 2
            url = apple_service.resolve_apple_url(entry.title, entry.artist)
            with SessionLocal() as inner_db:
                e = inner_db.query(QueueEntry).filter(QueueEntry.id == entry.id).first()
                if e:
                    e.apple_url = url
                    e.status = "resolved" if url else "error"
                    if not url:
                        e.error_msg = "Apple Music URL not found"
                    inner_db.commit()

    threading.Thread(target=_resolve_urls, daemon=True).start()

    # Build response queue
    response_queue = [
        QueueItem(
            position=e.position, 
            title=e.title, 
            artist=e.artist or "", 
            id=e.id,
            thumbnail_url=e.thumbnail or "",
            video_id=e.video_id or ""
        )
        for e in db_entries
    ]

    return SessionResponse(
        session_id=session_id,
        seed_title=seed_title,
        seed_artist=seed_artist,
        seed_thumbnail=seed_thumbnail,
        queue=response_queue
    )
