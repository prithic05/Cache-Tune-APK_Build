import time
import threading
from datetime import datetime, timedelta, timezone
from sqlalchemy.orm import Session
from sqlalchemy import text

from backend.db.database import SessionLocal
from backend.db.models import AppleWrapperSession
from backend.services.apple_wrapper_manager import stop_session

# Global Advisory Lock ID (arbitrary 64-bit integer)
CLEANUP_LOCK_ID = 827364827364

def _cleanup_task():
    while True:
        try:
            with SessionLocal() as db:
                # 1. Attempt to acquire global advisory lock (non-blocking)
                # pg_try_advisory_lock returns true if lock is acquired, false otherwise
                result = db.execute(text(f"SELECT pg_try_advisory_lock({CLEANUP_LOCK_ID})")).scalar()
                
                if result:
                    try:
                        # 2. We have the lock, perform cleanup
                        threshold = datetime.now(timezone.utc) - timedelta(minutes=20)
                        
                        # Find sessions that are not BUSY and haven't been used in 20 minutes
                        stale_sessions = db.query(AppleWrapperSession).filter(
                            AppleWrapperSession.status != "BUSY",
                            AppleWrapperSession.last_used_at < threshold
                        ).all()
                        
                        for session in stale_sessions:
                            print(f"[CleanupWorker] Stopping idle wrapper session for user {session.user_id} ({session.docker_project})")
                            try:
                                stop_session(db, session.docker_project)
                            except Exception as e:
                                print(f"[CleanupWorker] Error stopping session {session.docker_project}: {e}")
                                
                                            # Cleanup old cache files
                        from backend.services.cache_manager import CACHE_DIR, _safe_delete
                        import os as python_os
                        now = datetime.now(timezone.utc).timestamp()
                        if python_os.path.exists(CACHE_DIR):
                            for fname in python_os.listdir(CACHE_DIR):
                                fpath = python_os.path.join(CACHE_DIR, fname)
                                if python_os.path.isfile(fpath):
                                    if now - python_os.path.getmtime(fpath) > 1200: # 20 minutes
                                        _safe_delete(fpath)

                    finally:
                        # 3. Release the advisory lock
                        db.execute(text(f"SELECT pg_advisory_unlock({CLEANUP_LOCK_ID})"))
                        db.commit()
                        
        except Exception as e:
            print(f"[CleanupWorker] Exception: {e}")
            
        # Sleep for 5 minutes before checking again
        time.sleep(300)

def start_cleanup_worker():
    thread = threading.Thread(target=_cleanup_task, daemon=True)
    thread.start()
    print("[CleanupWorker] Background cleanup thread started.")
