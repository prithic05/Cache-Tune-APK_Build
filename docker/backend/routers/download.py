"""
Download router — permanent downloads to downloads/ folder.
Supports two formats per track:
  - ALAC (.m4a) — original lossless file
  - FLAC (.flac) — converted version for universal compatibility
"""

import os
import shutil
from fastapi import APIRouter, HTTPException, Depends
from fastapi.responses import FileResponse
from pydantic import BaseModel
from sqlalchemy.orm import Session
from typing import Optional
from datetime import datetime

from backend.db.database import get_db
from backend.db.models import QueueEntry
from backend.services import converter

router = APIRouter()

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOWNLOADS_DIR = os.path.join(os.path.dirname(BASE_DIR), "downloads")


class DownloadRequest(BaseModel):
    session_id: str
    position: int
    format: Optional[str] = "flac"  # "flac" | "alac"


@router.post("/download")
def request_download(req: DownloadRequest, db: Session = Depends(get_db)):
    """
    Copy a cached file to the permanent downloads/ folder.
    format: "flac" → serve FLAC (browser-compatible)
            "alac" → serve original ALAC .m4a (lossless)
    """
    entry = db.query(QueueEntry).filter(
        QueueEntry.session_id == req.session_id,
        QueueEntry.position == req.position
    ).first()

    if not entry:
        raise HTTPException(status_code=404, detail="Queue entry not found")

    fmt = (req.format or "flac").lower()

    if fmt == "alac":
        # Use the original .m4a
        source_path = entry.alac_path
        if not source_path or not os.path.exists(source_path):
            # Fallback: try deriving from cache_path
            if entry.cache_path:
                m4a_guess = os.path.splitext(entry.cache_path)[0] + ".m4a"
                if os.path.exists(m4a_guess):
                    source_path = m4a_guess
        if not source_path or not os.path.exists(source_path):
            raise HTTPException(
                status_code=404,
                detail="ALAC file not found. It may have been cleaned up already."
            )
        ext = ".m4a"
    else:
        # FLAC: use cache_path (already converted if ffmpeg available)
        source_path = entry.cache_path
        if not source_path or not os.path.exists(source_path):
            raise HTTPException(status_code=404, detail="No cached file found for this track")

        # If cache_path is .m4a (ffmpeg unavailable), convert on demand
        if source_path.endswith(".m4a"):
            flac_path = os.path.splitext(source_path)[0] + ".flac"
            if not os.path.exists(flac_path):
                if not converter.is_ffmpeg_available():
                    raise HTTPException(
                        status_code=503,
                        detail="FLAC conversion requires ffmpeg. Install ffmpeg and add to PATH."
                    )
                converted = converter.convert_to_flac(source_path, flac_path)
                if not converted:
                    raise HTTPException(status_code=500, detail="FLAC conversion failed")
                source_path = converted
            else:
                source_path = flac_path
        ext = ".flac"

    media_type = "audio/mp4" if ext == ".m4a" else "audio/flac"

    # Build clean permanent destination filename
    clean_artist = "".join(c if c.isalnum() or c in " -_." else "_" for c in (entry.artist or "")).strip()
    clean_title = "".join(c if c.isalnum() or c in " -_." else "_" for c in (entry.title or "track")).strip()
    
    if clean_artist and clean_title:
        dest_name = f"{clean_artist} - {clean_title}"[:120].strip() + ext
    else:
        dest_name = (clean_title or "track")[:120].strip() + ext

    # Also save a copy to the server's downloads/ directory
    try:
        dest_dir = os.path.join(DOWNLOADS_DIR, entry.session_id[:8])
        os.makedirs(dest_dir, exist_ok=True)
        dest_path = os.path.join(dest_dir, dest_name)
        if not os.path.exists(dest_path):
            shutil.copy2(source_path, dest_path)
    except Exception as e:
        print(f"[Download] Failed to save copy to downloads dir: {e}")

    # Return the file directly as an attachment to trigger browser download
    return FileResponse(
        path=source_path,
        media_type=media_type,
        filename=dest_name,
        headers={
            "Content-Disposition": f'attachment; filename="{dest_name}"',
            "Access-Control-Expose-Headers": "Content-Disposition"
        }
    )


@router.get("/downloads/list")
def list_downloads(db: Session = Depends(get_db)):
    """List all permanently saved downloads."""
    os.makedirs(DOWNLOADS_DIR, exist_ok=True)
    files = []
    for root, _, filenames in os.walk(DOWNLOADS_DIR):
        for fname in filenames:
            if fname.endswith((".m4a", ".flac", ".mp3")):
                fpath = os.path.join(root, fname)
                files.append({
                    "filename": fname,
                    "path": fpath,
                    "size_mb": round(os.path.getsize(fpath) / 1024 / 1024, 2),
                    "format": fname.split(".")[-1].upper(),
                })
    return {"downloads": files, "count": len(files)}
