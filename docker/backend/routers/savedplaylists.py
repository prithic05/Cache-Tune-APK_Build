from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.orm import Session
from sqlalchemy.exc import IntegrityError
from pydantic import BaseModel
from typing import Optional
from backend.db.database import get_db
from backend.db.models import SavedPlaylist
from backend.routers.auth import get_current_user

router = APIRouter(prefix="/api/playlists", tags=["saved_playlists"])

class SavePlaylistIn(BaseModel):
    playlist_id: str
    name: Optional[str] = None
    description: Optional[str] = None
    thumbnail: Optional[str] = None
    provider: Optional[str] = 'youtube'

def sp_to_dict(sp: SavedPlaylist):
    return {
        "id": sp.id,
        "playlist_id": sp.playlist_id,
        "name": sp.name,
        "description": sp.description,
        "thumbnail": sp.thumbnail,
        "provider": sp.provider,
        "created_at": sp.created_at.isoformat() if sp.created_at else None,
    }

@router.get("/saved")
def get_saved_playlists(request: Request, db: Session = Depends(get_db), user=Depends(get_current_user)):
    items = db.query(SavedPlaylist).filter(SavedPlaylist.user_id == user.id).order_by(SavedPlaylist.created_at.desc()).all()
    return [sp_to_dict(i) for i in items]

@router.get("/{playlist_id}/save-status")
def get_save_status(playlist_id: str, request: Request, db: Session = Depends(get_db), user=Depends(get_current_user)):
    exists = db.query(SavedPlaylist).filter(SavedPlaylist.user_id == user.id, SavedPlaylist.playlist_id == playlist_id).first()
    return {"is_saved": exists is not None}

@router.post("/{playlist_id}/save")
def save_playlist(playlist_id: str, req: SavePlaylistIn, request: Request, db: Session = Depends(get_db), user=Depends(get_current_user)):
    existing = db.query(SavedPlaylist).filter(SavedPlaylist.user_id == user.id, SavedPlaylist.playlist_id == playlist_id).first()
    if existing:
        return sp_to_dict(existing)
    sp = SavedPlaylist(
        user_id=user.id,
        playlist_id=playlist_id,
        name=req.name,
        description=req.description,
        thumbnail=req.thumbnail,
        provider=req.provider,
    )
    db.add(sp)
    try:
        db.commit()
        db.refresh(sp)
    except IntegrityError:
        db.rollback()
        existing = db.query(SavedPlaylist).filter(SavedPlaylist.user_id == user.id, SavedPlaylist.playlist_id == playlist_id).first()
        return sp_to_dict(existing)
    return sp_to_dict(sp)

@router.delete("/{playlist_id}/save")
def unsave_playlist(playlist_id: str, request: Request, db: Session = Depends(get_db), user=Depends(get_current_user)):
    deleted = db.query(SavedPlaylist).filter(SavedPlaylist.user_id == user.id, SavedPlaylist.playlist_id == playlist_id).delete()
    db.commit()
    if deleted == 0:
        raise HTTPException(status_code=404, detail="Saved playlist not found")
    return {"message": "Playlist unsaved"}
