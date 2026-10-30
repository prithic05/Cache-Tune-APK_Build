"""
Home router — GET /api/home, POST /api/home/feed
Returns YouTube Music home sections.
"""
from fastapi import APIRouter, Depends, HTTPException
from backend.routers.auth import get_current_user
from backend.db.models import User
from backend.db.database import get_db
from sqlalchemy.orm import Session
from typing import List, Dict, Any, Optional
from pydantic import BaseModel
from backend.services import ytmusic_service
import json
import time
import hashlib
import re
import logging

logger = logging.getLogger(__name__)

router = APIRouter()

class HomeFeedRequest(BaseModel):
    headers: Optional[str] = None
    limit: Optional[int] = 15

def prepare_ytmusic_auth(auth_str: Optional[str]) -> Optional[str]:
    """
    Accepts raw HTTP DevTools headers or JSON, extracts cookies & credentials,
    and returns a valid ytmusicapi-compatible browser auth JSON string.
    """
    if not auth_str or not auth_str.strip():
        return None
    raw = auth_str.strip()
    try:
        if raw.startswith("{"):
            headers = json.loads(raw)
        else:
            headers = {}
            for line in raw.splitlines():
                line = line.strip()
                if not line or line.startswith("#") or line.startswith(":"):
                    continue
                if ":" in line:
                    k, v = line.split(":", 1)
                    headers[k.strip().lower()] = v.strip()

        cookie = headers.get("cookie", "")
        if not cookie:
            return None

        sapisid = None
        for key in ["__Secure-3PAPISID", "__Secure-1PAPISID", "SAPISID", "APISID"]:
            m = re.search(r'(?:^|[\s;])' + re.escape(key) + r'\s*=\s*(?:\"([^\"]+)\"|([^;\s]+))', cookie)
            if m:
                sapisid = m.group(1) or m.group(2)
                if sapisid:
                    sapisid = sapisid.strip().strip('"')
                    break

        if sapisid:
            if "__Secure-3PAPISID" not in cookie:
                cookie += f"; __Secure-3PAPISID={sapisid}"
            if "SOCS" not in cookie:
                cookie += "; SOCS=CAI"
            headers["cookie"] = cookie
            origin = headers.get("origin") or headers.get("x-origin") or "https://music.youtube.com"
            headers["origin"] = origin
            t = int(time.time())
            h = hashlib.sha1(f"{t} {sapisid} {origin}".encode()).hexdigest()
            headers["authorization"] = f"SAPISIDHASH {t}_{h}"
            if "x-goog-authuser" not in headers:
                headers["x-goog-authuser"] = "0"
            return json.dumps(headers)
    except Exception as e:
        logger.warning(f"Error preparing ytmusic auth: {e}")
    return None

@router.post("/feed", response_model=List[Dict[str, Any]])
@router.post("/home/feed", response_model=List[Dict[str, Any]])
@router.get("/feed", response_model=List[Dict[str, Any]])
def get_home_feed(request: Optional[HomeFeedRequest] = None, limit: int = 15):
    """
    Direct endpoint for CacheTune Android & Web to get full 15-shelf YouTube Music Home feed
    using provided user credentials or guest mode without requiring a login session.
    """
    auth_str = request.headers if request and request.headers else None
    req_limit = request.limit if request and request.limit else limit
    
    from ytmusicapi import YTMusic
    yt = None
    prepared_auth = prepare_ytmusic_auth(auth_str)
    if prepared_auth:
        try:
            yt = YTMusic(auth=prepared_auth)
            logger.info("Initialized authenticated YTMusic client for Home Feed")
        except Exception as e:
            logger.warning(f"Failed to initialize authenticated YTMusic: {e}")

    if yt is None:
        try:
            yt = YTMusic()
        except Exception as e:
            raise HTTPException(status_code=500, detail=str(e))
            
    try:
        home_data = yt.get_home(limit=req_limit)
        sections = []
        if not isinstance(home_data, list):
            return []
            
        for section in home_data:
            if not isinstance(section, dict):
                continue
            title = section.get("title", "")
            contents = section.get("contents", [])
            if not isinstance(contents, list):
                continue
            parsed_contents = []
            for item in contents:
                if not isinstance(item, dict):
                    continue
                parsed_contents.append({
                    "title": item.get("title", ""),
                    "videoId": item.get("videoId"),
                    "playlistId": item.get("playlistId"),
                    "browseId": item.get("browseId"),
                    "thumbnail": ytmusic_service.get_best_thumbnail(item.get("thumbnails") or item.get("thumbnail")),
                    "description": item.get("description", ""),
                    "artist": ytmusic_service._extract_artist(item, fallback="")
                })
            if parsed_contents:
                sections.append({
                    "title": title or "Recommended",
                    "contents": parsed_contents
                })
        return sections
    except Exception as e:
        logger.error(f"Error fetching home feed: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@router.get("/home", response_model=List[Dict[str, Any]])
def get_home(limit: int = 15, db: Session = Depends(get_db), current_user: Optional[User] = Depends(get_current_user)):
    try:
        if current_user and ytmusic_service.is_auth_valid(current_user.id, db):
            return ytmusic_service.get_home(db, current_user.id, limit=limit)
        return get_home_feed(limit=limit)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
