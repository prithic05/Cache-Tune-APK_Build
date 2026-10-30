"""
Cache manager — handles temp cache lifecycle.

Pipeline after download:
  1. Go downloader produces .m4a (ALAC)
  2. We auto-convert .m4a -> .flac for browser playback
  3. The .flac is what gets served via /api/stream/
  4. The original .m4a is kept alongside for ALAC download
  
Cache structure per session:
  cache/
    cache_<sessionid>_<pos>.m4a    <- original ALAC
    cache_<sessionid>_<pos>.flac   <- converted for browser

On skip: both files deleted.
On session end: all files for that session deleted.
"""

import os
import threading
import subprocess
from typing import Optional
from sqlalchemy.orm import Session as DBSession

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE_DIR = os.path.join(os.path.dirname(BASE_DIR), "cache")


def _safe_delete(path: str):
    """Delete a file safely, ignore errors."""
    try:
        if path and os.path.exists(path):
            os.remove(path)
            print(f"[CacheManager] Deleted: {path}")
    except Exception as e:
        print(f"[CacheManager] Failed to delete {path}: {e}")


def make_cache_basename(entry_id: str) -> str:
    """Generate a deterministic cache base name (no extension)."""
    safe_id = entry_id.replace("-", "")[:16]
    return f"cache_{safe_id}"


def get_cache_path(entry_id: str, ext: str = ".m4a") -> str:
    """Full path to a cache file."""
    os.makedirs(CACHE_DIR, exist_ok=True)
    return os.path.join(CACHE_DIR, make_cache_basename(entry_id) + ext)


def get_flac_path(m4a_path: str) -> str:
    """Given a .m4a cache path, return the expected .flac path."""
    return os.path.splitext(m4a_path)[0] + ".flac"


def delete_cached_file(cache_path: Optional[str]):
    """Delete a cached .m4a and its .flac sibling (called on skip)."""
    if not cache_path:
        return
    _safe_delete(cache_path)
    # Also delete the FLAC companion
    flac_path = get_flac_path(cache_path)
    _safe_delete(flac_path)


def wipe_session_cache(session_id: str, db: Optional[DBSession] = None):
    """
    Delete all cached files for a session (called on session end).
    Removes both .m4a and .flac files.
    """
    from backend.db.models import QueueEntry

    active_statuses = ("cached", "playing", "resolving")

    if db:
        entries = db.query(QueueEntry).filter(
            QueueEntry.session_id == session_id,
            QueueEntry.cache_path.isnot(None),
            QueueEntry.status.in_(active_statuses)
        ).all()
        for entry in entries:
            _safe_delete(entry.cache_path)
            _safe_delete(get_flac_path(entry.cache_path))
            entry.cache_path = None
            entry.status = "skipped"
        db.commit()
    else:
        # Fallback: delete all cache files matching session prefix
        safe_session = session_id.replace("-", "")[:12]
        prefix = f"cache_{safe_session}_"
        os.makedirs(CACHE_DIR, exist_ok=True)
        for fname in os.listdir(CACHE_DIR):
            if fname.startswith(prefix):
                _safe_delete(os.path.join(CACHE_DIR, fname))

def pre_cache_tracks(entries_info: list, session_id: str, user_id: int, current_position: int = 0, db_session_factory=None):
    """
    Delegate caching to the global DownloadManager.
    entries_info: list of dicts {id, position, apple_url, song_title, artist}
    """
    from backend.services.download_manager import download_manager
    download_manager.update_state(session_id, current_position, entries_info, user_id)

def start_auto_cleaner():
    """
    Starts a background daemon thread that scans the CACHE_DIR every 10 minutes
    and deletes files that were created more than 40 minutes ago.
    """
    import time
    
    def _cleaner_loop():
        while True:
            try:
                if os.path.exists(CACHE_DIR):
                    current_time = time.time()
                    for filename in os.listdir(CACHE_DIR):
                        filepath = os.path.join(CACHE_DIR, filename)
                        if os.path.isfile(filepath):
                            # Get file creation/modification time
                            file_time = os.path.getctime(filepath)
                            # If older than 40 minutes (2400 seconds)
                            if (current_time - file_time) > 2400:
                                try:
                                    os.remove(filepath)
                                    print(f"[CacheCleaner] Deleted old cache file: {filename}")
                                except Exception as e:
                                    print(f"[CacheCleaner] Failed to delete {filename}: {e}")
            except Exception as e:
                print(f"[CacheCleaner] Loop error: {e}")
            
            # Sleep for 10 minutes before next scan
            time.sleep(600)

    cleaner_thread = threading.Thread(target=_cleaner_loop, daemon=True)
    cleaner_thread.start()
    print("[CacheCleaner] Started background auto-cleaner thread (40m retention)")
