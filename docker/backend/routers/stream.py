"""
Stream router — GET /api/stream/{filename}
Serves audio files from cache/ with Range header support for seekable playback.
"""

import os
import mimetypes
import asyncio
from fastapi import APIRouter, HTTPException, Request, Depends, BackgroundTasks
from fastapi.responses import StreamingResponse, Response
from sqlalchemy.orm import Session

from backend.db.database import get_db
from backend.db.models import QueueEntry, User
from backend.routers.auth import get_current_user

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE_DIR = os.path.join(os.path.dirname(BASE_DIR), "cache")

router = APIRouter()

CHUNK_SIZE = 1024 * 512  # 512KB chunks


def get_content_type(filename: str) -> str:
    if filename.endswith(".m4a"):
        return "audio/mp4"
    elif filename.endswith(".mp3"):
        return "audio/mpeg"
    elif filename.endswith(".flac"):
        return "audio/flac"
    elif filename.endswith(".ogg"):
        return "audio/ogg"
    return "audio/flac"   # default to flac since that's what we serve


@router.api_route("/stream/entry/{entry_id}", methods=["GET", "HEAD"])
async def stream_audio_by_entry(
    entry_id: str, 
    request: Request, 
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    """Stream an audio file by queue entry ID, waiting if it's currently downloading."""
    entry = db.query(QueueEntry).filter(QueueEntry.id == entry_id).first()
    if not entry:
        raise HTTPException(status_code=404, detail="Entry not found")

    # Trigger precache for this track and the next ones immediately in a detached thread
    # We don't use BackgroundTasks because they run AFTER the streaming response finishes (4 minutes later)
    import threading
    from backend.db.database import SessionLocal
    from backend.routers.player import _trigger_precache

    def run_precache(sid, pos, uid):
        db_session = SessionLocal()
        try:
            _trigger_precache(sid, pos, db_session, uid)
        finally:
            db_session.close()

    threading.Thread(target=run_precache, args=(entry.session_id, entry.position, current_user.id), daemon=True).start()


    # Wait up to 30 seconds for the cache_path to be populated and the file to exist
    max_retries = 30
    for _ in range(max_retries):
        db.refresh(entry)
        if entry.cache_path and os.path.exists(entry.cache_path):
            break
        if entry.status == "error":
            raise HTTPException(status_code=500, detail="Track failed to download")
        await asyncio.sleep(1)
        
    if not entry.cache_path or not os.path.exists(entry.cache_path):
        raise HTTPException(status_code=404, detail="Audio file not ready")

    return await stream_audio(os.path.basename(entry.cache_path), request)


@router.api_route("/stream/{filename}", methods=["GET", "HEAD"])
async def stream_audio(filename: str, request: Request):
    """Stream an audio file with Range header support."""
    # Security: only allow files from cache dir
    safe_filename = os.path.basename(filename)
    file_path = os.path.join(CACHE_DIR, safe_filename)

    if not os.path.exists(file_path):
        raise HTTPException(status_code=404, detail=f"Audio file not found: {safe_filename}")

    file_size = os.path.getsize(file_path)
    content_type = get_content_type(safe_filename)

    if request.method == "HEAD":
        headers = {
            "Accept-Ranges": "bytes",
            "Content-Length": str(file_size),
            "Content-Type": content_type,
        }
        return Response(status_code=200, headers=headers)

    range_header = request.headers.get("Range")

    if range_header:
        # Parse Range: bytes=start-end
        try:
            range_val = range_header.strip().split("=")[1]
            start_str, end_str = range_val.split("-")
            start = int(start_str)
            end = int(end_str) if end_str else file_size - 1
        except Exception:
            raise HTTPException(status_code=400, detail="Invalid Range header")

        if start >= file_size or end >= file_size:
            raise HTTPException(
                status_code=416,
                detail="Range Not Satisfiable",
                headers={"Content-Range": f"bytes */{file_size}"}
            )

        chunk_length = end - start + 1

        def iterfile():
            with open(file_path, "rb") as f:
                f.seek(start)
                remaining = chunk_length
                while remaining > 0:
                    read_size = min(CHUNK_SIZE, remaining)
                    data = f.read(read_size)
                    if not data:
                        break
                    yield data
                    remaining -= len(data)

        headers = {
            "Content-Range": f"bytes {start}-{end}/{file_size}",
            "Accept-Ranges": "bytes",
            "Content-Length": str(chunk_length),
            "Content-Type": content_type,
        }
        return StreamingResponse(iterfile(), status_code=206, headers=headers)

    else:
        # Full file response
        def iterfile():
            with open(file_path, "rb") as f:
                while True:
                    chunk = f.read(CHUNK_SIZE)
                    if not chunk:
                        break
                    yield chunk

        headers = {
            "Accept-Ranges": "bytes",
            "Content-Length": str(file_size),
            "Content-Type": content_type,
        }
        return StreamingResponse(iterfile(), status_code=200, headers=headers)
