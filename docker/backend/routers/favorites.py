from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.orm import Session
from sqlalchemy.exc import IntegrityError
from pydantic import BaseModel
from typing import Optional
from backend.db.database import get_db
from backend.db.models import Favorite
from backend.routers.auth import get_current_user

router = APIRouter(prefix="/api/favorites", tags=["favorites"])

class FavoriteIn(BaseModel):
    song_id: str
    title: Optional[str] = None
    artist: Optional[str] = None
    thumbnail: Optional[str] = None
    provider: Optional[str] = 'youtube'

def fav_to_dict(f: Favorite):
    return {
        "id": f.id,
        "song_id": f.song_id,
        "title": f.title,
        "artist": f.artist,
        "thumbnail": f.thumbnail,
        "provider": f.provider,
        "created_at": f.created_at.isoformat() if f.created_at else None,
    }

@router.get("")
def get_favorites(request: Request, db: Session = Depends(get_db), user=Depends(get_current_user)):
    favs = db.query(Favorite).filter(Favorite.user_id == user.id).order_by(Favorite.created_at.desc()).all()
    return [fav_to_dict(f) for f in favs]

@router.get("/{song_id}/status")
def get_favorite_status(song_id: str, request: Request, db: Session = Depends(get_db), user=Depends(get_current_user)):
    exists = db.query(Favorite).filter(Favorite.user_id == user.id, Favorite.song_id == song_id).first()
    return {"is_favorite": exists is not None}

@router.post("")
def add_favorite(req: FavoriteIn, request: Request, db: Session = Depends(get_db), user=Depends(get_current_user)):
    existing = db.query(Favorite).filter(Favorite.user_id == user.id, Favorite.song_id == req.song_id).first()
    if existing:
        return fav_to_dict(existing)
    fav = Favorite(
        user_id=user.id,
        song_id=req.song_id,
        title=req.title,
        artist=req.artist,
        thumbnail=req.thumbnail,
        provider=req.provider,
    )
    db.add(fav)
    try:
        db.commit()
        db.refresh(fav)
    except IntegrityError:
        db.rollback()
        existing = db.query(Favorite).filter(Favorite.user_id == user.id, Favorite.song_id == req.song_id).first()
        return fav_to_dict(existing)
    return fav_to_dict(fav)

@router.delete("/{song_id}")
def remove_favorite(song_id: str, request: Request, db: Session = Depends(get_db), user=Depends(get_current_user)):
    deleted = db.query(Favorite).filter(Favorite.user_id == user.id, Favorite.song_id == song_id).delete()
    db.commit()
    if deleted == 0:
        raise HTTPException(status_code=404, detail="Favorite not found")
    return {"message": "Removed from favorites"}
