"""
Playlist router — GET /api/playlist/{playlist_id}
Returns playlist details and tracks.
"""
from fastapi import APIRouter, Depends, HTTPException
from backend.routers.auth import get_current_user
from backend.db.models import User
from backend.db.database import get_db
from sqlalchemy.orm import Session
from typing import Dict, Any
from backend.services import ytmusic_service

router = APIRouter()

@router.get("/playlist/{playlist_id}", response_model=Dict[str, Any])
def get_playlist(playlist_id: str, db=Depends(get_db), current_user: User = Depends(get_current_user)):
    try:
        if not ytmusic_service.is_auth_valid(current_user.id, db):
            raise HTTPException(status_code=401, detail="YouTube Music not authenticated")
        return ytmusic_service.get_playlist(db, current_user.id, playlist_id)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
