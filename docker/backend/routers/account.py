from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session
from datetime import datetime, timezone

from backend.db.database import get_db
from backend.db.models import User
from backend.routers.auth import get_current_user, UpdateYTAuthRequest, UpdateAppleRequest
from backend.utils.security import encrypt_data

router = APIRouter(prefix="/api/account", tags=["account"])

@router.get("")
def get_account_status(current_user: User = Depends(get_current_user)):
    return {
        "id": current_user.id,
        "username": current_user.username or current_user.email.split('@')[0],
        "email": current_user.email,
        "is_verified": current_user.is_verified,
        "created_at": current_user.created_at,
        "yt_configured": bool(current_user.yt_auth_headers),
        "apple_configured": bool(current_user.apple_username and current_user.apple_password)
    }

@router.put("/youtube")
def update_youtube_auth(req: UpdateYTAuthRequest, current_user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    from fastapi import HTTPException
    import json
    import ytmusicapi
    
    raw = req.yt_auth_headers.strip()
    if not raw:
        raise HTTPException(status_code=400, detail="Headers cannot be empty")

    try:
        auth_json_str = ytmusicapi.setup(filepath=None, headers_raw=raw)
        headers = json.loads(auth_json_str)
        
        # Inject authorization if missing
        if "authorization" not in headers and "cookie" in headers:
            from ytmusicapi.helpers import get_authorization
            try:
                headers["authorization"] = get_authorization(headers["cookie"])
            except Exception:
                pass
                
        current_user.yt_auth_headers = encrypt_data(json.dumps(headers))
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Failed to parse YouTube headers: {str(e)}")

    current_user.yt_credentials_updated_at = datetime.now(timezone.utc)
    db.commit()
    return {"message": "YouTube authentication headers updated successfully."}

@router.put("/apple")
def update_apple_credentials(req: UpdateAppleRequest, current_user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    current_user.apple_username = encrypt_data(req.apple_username)
    current_user.apple_password = encrypt_data(req.apple_password)
    current_user.apple_credentials_updated_at = datetime.now(timezone.utc)
    db.commit()
    return {"message": "Apple credentials updated successfully."}
