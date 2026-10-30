"""
Config router — YT Music auth and Apple Music (Docker wrapper) setup.
No credentials are stored in the database.
"""

import os
import subprocess
import json
import time
import threading
from fastapi import APIRouter, Depends, HTTPException
from backend.routers.auth import get_current_user
from backend.db.models import User
from backend.db.database import get_db
from sqlalchemy.orm import Session
from pydantic import BaseModel
from typing import Optional

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROJECT_ROOT = os.path.dirname(BASE_DIR)
YT_MUSIC_DIR = os.path.join(PROJECT_ROOT, "yt-music")
BROWSER_JSON = os.path.join(YT_MUSIC_DIR, "browser.json")
WRAPPER_DIR = os.path.join(PROJECT_ROOT, "Downloader", "wrapper")

router = APIRouter()

# Stream SSE for build progress
_docker_build_log = []
_docker_build_running = False


class YTMusicAuthRequest(BaseModel):
    """Raw request headers copied from YouTube Music DevTools network tab."""
    raw_headers: str


class AppleMusicAuthRequest(BaseModel):
    username: Optional[str] = None
    password: Optional[str] = None
    media_user_token: Optional[str] = None
    storefront: Optional[str] = None


class DockerBuildRequest(BaseModel):
    pass


def _parse_raw_headers_fallback(raw_str: str) -> dict:
    """Fallback parser for pasted headers (supports DevTools, cURL, and raw key-value format)."""
    import re
    headers = {}
    raw_str = raw_str.strip()
    
    # Check for cURL format
    if 'curl' in raw_str.lower() or '-h' in raw_str.lower():
        pattern = r'(?:-h|--header)\s+[\'"](?::)?([^\':]+):\s*([^\'"]+)[\'"]'
        matches = re.findall(pattern, raw_str, re.IGNORECASE)
        for k, v in matches:
            headers[k.strip().lower()] = v.strip()
            
    if not headers or 'cookie' not in headers:
        curr_key = None
        for line in raw_str.splitlines():
            line_s = line.strip()
            if not line_s or line_s.startswith('GET ') or line_s.startswith('POST ') or line_s.startswith('HTTP/'):
                continue
            if ':' in line_s and not line_s.startswith('http://') and not line_s.startswith('https://'):
                parts = line_s.split(':', 1)
                k = parts[0].strip().lstrip(':').lower()
                v = parts[1].strip()
                if k:
                    headers[k] = v
                    curr_key = k
            elif curr_key and line_s and not line_s.startswith('-H') and not line_s.startswith('curl'):
                headers[curr_key] += ' ' + line_s

    # Sensible defaults for missing optional headers
    if "user-agent" not in headers:
        headers["user-agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    if "accept" not in headers:
        headers["accept"] = "*/*"
    if "accept-language" not in headers:
        headers["accept-language"] = "en-US,en;q=0.9"
    if "x-goog-authuser" not in headers:
        headers["x-goog-authuser"] = "0"
    if "origin" not in headers:
        headers["origin"] = "https://music.youtube.com"

    return headers


@router.post("/config/ytmusic")
def configure_ytmusic(req: YTMusicAuthRequest, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    """
    Parse pasted raw HTTP request headers from YouTube Music and store them securely in the database.
    """
    try:
        from ytmusicapi import YTMusic
        import ytmusicapi
        import json
        from backend.utils.security import encrypt_data
        from backend.services.ytmusic_service import _compute_fresh_authorization, clean_yt_cookie

        raw = req.raw_headers.strip()
        if not raw:
            raise HTTPException(status_code=400, detail="Headers cannot be empty")

        headers = None
        # Check if the user pasted raw JSON directly
        if raw.startswith("{") and raw.endswith("}"):
            try:
                headers = json.loads(raw)
            except Exception:
                headers = None

        if not headers:
            # Try standard ytmusicapi setup first
            try:
                auth_json_str = ytmusicapi.setup(filepath=None, headers_raw=raw)
                headers = json.loads(auth_json_str)
            except Exception as setup_err:
                # Fallback to our robust parser if ytmusicapi parser failed (e.g., cURL format or single-line DevTools format)
                try:
                    headers = _parse_raw_headers_fallback(raw)
                except Exception:
                    raise HTTPException(status_code=400, detail=f"Invalid headers format: {setup_err}")

        if not headers or "cookie" not in headers:
            raise HTTPException(status_code=400, detail="Headers missing 'cookie' field. Please copy headers from a logged-in YouTube Music tab.")

        if "origin" not in headers:
            headers["origin"] = "https://music.youtube.com"

        # Ensure __Secure-3PAPISID is present for ytmusicapi compatibility
        headers["cookie"] = clean_yt_cookie(headers["cookie"])

        # Compute initial authorization header
        fresh_auth = _compute_fresh_authorization(headers)
        if fresh_auth:
            headers["authorization"] = fresh_auth

        # Verify authentication by instantiating YTMusic and making a lightweight API call (get_home)
        try:
            yt = YTMusic(auth=json.dumps(headers))
            # Test with get_home (does NOT throw an error if watch history is paused/empty)
            try:
                yt.get_home(limit=1)
            except Exception:
                # Secondary fallback verification check
                yt.search("test", limit=1)
        except Exception as verify_err:
            err_msg = str(verify_err).strip()
            if not err_msg or err_msg == "None":
                err_msg = repr(verify_err)
            return {
                "status": "error",
                "message": f"Authentication failed! Please ensure you copied fresh headers from a logged-in YouTube Music session. Error: {err_msg}"
            }
            
        from datetime import datetime
        current_user.yt_auth_headers = encrypt_data(json.dumps(headers))
        current_user.yt_credentials_updated_at = datetime.utcnow()
        db.commit()

        # Invalidate any cached YTMusic instance for this user
        from backend.services.ytmusic_service import clear_yt_instance_cache
        clear_yt_instance_cache(current_user.id)

        return {"status": "success", "message": "YouTube Music authentication configured successfully."}

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"YT Music config error: {str(e)}")


@router.post("/config/applemusic")
def configure_applemusic(req: AppleMusicAuthRequest, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    """
    Save encrypted Apple Music credentials to the user's database record.
    The Docker wrapper will spin up on demand per-user when needed.
    """
    import re
    from backend.utils.security import encrypt_data
    from datetime import datetime

    # Username and password are optional if media_user_token is provided
    if not req.media_user_token and (not req.username or not req.password):
        raise HTTPException(status_code=400, detail="Either Media User Token or Username/Password required")

    # Update config.yaml with media-user-token
    if req.media_user_token:
        config_path = os.path.join(PROJECT_ROOT, "Downloader", "apple-music-downloader", "config.yaml")
        if os.path.exists(config_path):
            try:
                with open(config_path, "r", encoding="utf-8") as f:
                    content = f.read()
                
                # Replace media-user-token: "..." with the new token
                content = re.sub(
                    r'media-user-token:\s*".*?"', 
                    f'media-user-token: "{req.media_user_token.strip()}"', 
                    content
                )
                if req.storefront:
                    content = re.sub(
                        r'storefront:\s*".*?"',
                        f'storefront: "{req.storefront.strip().lower()}"',
                        content
                    )
                
                with open(config_path, "w", encoding="utf-8") as f:
                    f.write(content)
            except Exception as e:
                print(f"[Config] Error updating media-user-token: {e}")

    try:
        if req.username:
            current_user.apple_username = encrypt_data(req.username)
        if req.password:
            current_user.apple_password = encrypt_data(req.password)
        current_user.apple_credentials_updated_at = datetime.utcnow()
        db.commit()
        
        # Kill any existing wrapper session for this user so it restarts with fresh credentials
        from backend.db.models import AppleWrapperSession
        import subprocess
        
        session_record = db.query(AppleWrapperSession).filter(AppleWrapperSession.user_id == current_user.id).first()
        if session_record:
            try:
                COMPOSE_FILE = os.path.join(PROJECT_ROOT, "backend", "services", "apple-wrapper.yml")
                subprocess.run(["docker", "compose", "-p", session_record.docker_project, "-f", COMPOSE_FILE, "down", "-v"], check=False, capture_output=True)
            except Exception:
                pass
            db.delete(session_record)
            db.commit()
            
        return {"status": "success", "message": "Apple Music credentials saved securely"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to save credentials: {str(e)}")


@router.post("/config/docker/build")
def build_docker_image():
    """Build the Apple Music wrapper Docker image."""
    global _docker_build_running, _docker_build_log

    if _docker_build_running:
        raise HTTPException(status_code=409, detail="Docker build already in progress")

    _docker_build_running = True
    _docker_build_log = []

    def _run_build():
        global _docker_build_running
        from backend.services.docker_service import build_docker_image as _build

        def _cb(line):
            _docker_build_log.append(line)

        success, message = _build(progress_callback=_cb)
        _docker_build_log.append(f"BUILD {'SUCCESS' if success else 'FAILED'}: {message}")
        _docker_build_running = False

    threading.Thread(target=_run_build, daemon=True).start()
    return {"status": "started", "message": "Docker build started. Check /api/config/docker/build/status for progress."}


@router.get("/config/docker/build/status")
def docker_build_status():
    """Poll Docker build log and status."""
    return {
        "running": _docker_build_running,
        "log": _docker_build_log[-50:]  # Last 50 lines
    }


@router.post("/config/wrapper/stop")
def stop_wrapper():
    """Stop the running Apple Music wrapper container."""
    from backend.services.docker_service import stop_wrapper as _stop
    _stop()
    return {"status": "stopped"}


@router.post("/config/clear-cache")
def clear_cache():
    """Clear the converted FLACs and web cache directory."""
    import shutil
    cache_dir = os.path.join(PROJECT_ROOT, "cache")
    if os.path.exists(cache_dir):
        shutil.rmtree(cache_dir, ignore_errors=True)
    os.makedirs(cache_dir, exist_ok=True)
    return {"status": "success", "message": "Web cache (FLACs) cleared successfully."}


@router.post("/config/clear-downloads")
def clear_downloads():
    """Clear the raw ALACs downloaded by the Go downloader."""
    import shutil
    downloads_dir = os.path.join(PROJECT_ROOT, "Downloader", "apple-music-downloader", "AM-DL downloads")
    if os.path.exists(downloads_dir):
        shutil.rmtree(downloads_dir, ignore_errors=True)
    os.makedirs(downloads_dir, exist_ok=True)
    return {"status": "success", "message": "Raw downloads (ALACs) cleared successfully."}


@router.post("/config/applemusic/mutoken")
def save_mutoken(req: dict):
    """
    Save the media-user-token to Downloader config.yaml separately.
    """
    import re
    token = req.get("media_user_token", "").strip()
    if not token:
        raise HTTPException(status_code=400, detail="Token cannot be empty")
        
    config_path = os.path.join(PROJECT_ROOT, "Downloader", "apple-music-downloader", "config.yaml")
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                content = f.read()
            
            content = re.sub(
                r'media-user-token:\s*".*?"', 
                f'media-user-token: "{token}"', 
                content
            )
            
            with open(config_path, "w", encoding="utf-8") as f:
                f.write(content)
            return {"status": "success", "message": "Media-User-Token saved successfully!"}
        except Exception as e:
            raise HTTPException(status_code=500, detail=f"Failed to save token: {e}")
    else:
        raise HTTPException(status_code=500, detail="Downloader config.yaml not found.")


@router.get("/config/status")
def get_config_status(db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    """Return authentication/connection status for both services."""
    import re
    from backend.services.ytmusic_service import is_auth_valid, get_credentials_age_days

    yt_ok = is_auth_valid(current_user.id, db)
    yt_age_days = get_credentials_age_days(current_user.id, db)

    # Flag for the UI: warn if headers were saved over 300 days ago (cookie age warning)
    # or if the actual auth check failed (most useful signal)
    yt_needs_refresh = (not yt_ok) and bool(current_user.yt_auth_headers)
    
    apple_ok = bool(current_user.apple_username and current_user.apple_password)
    
    mutoken_configured = False
    config_path = os.path.join(PROJECT_ROOT, "Downloader", "apple-music-downloader", "config.yaml")
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                content = f.read()
                match = re.search(r'media-user-token:\s*"(.*?)"', content)
                if match and match.group(1) != "your-media-user-token" and match.group(1) != "":
                    mutoken_configured = True
        except:
            pass

    return {
        "ytmusic": {
            "authenticated": yt_ok,
            "needs_refresh": yt_needs_refresh,
            "credentials_age_days": yt_age_days,
            "browser_json_path": BROWSER_JSON,
            "message": "Connected" if yt_ok else (
                "Headers saved but authentication is failing — please re-paste fresh headers."
                if yt_needs_refresh else
                "Not configured. Paste headers in Configuration."
            )
        },
        "applemusic": {
            "wrapper_running": apple_ok,
            "message": "Connected" if apple_ok else "Apple Music credentials not configured",
            "mutoken_configured": mutoken_configured
        }
    }

