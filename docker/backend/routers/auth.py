from typing import Optional
from fastapi import APIRouter, Depends, HTTPException, status, Response, Request
from sqlalchemy.orm import Session
from sqlalchemy.exc import IntegrityError
from datetime import datetime, timedelta, timezone
from pydantic import BaseModel, EmailStr, Field
import re

from backend.db.database import get_db
from backend.db.models import User, OTP, Session as DBSession
from backend.utils.security import (
    hash_password, verify_password, 
    generate_otp, hash_otp, verify_otp,
    generate_session_token, encrypt_data
)
from backend.utils.email import send_otp_email

router = APIRouter(prefix="/api/auth", tags=["auth"])

# --- Schemas ---
class SendOTPRequest(BaseModel):
    email: EmailStr

class VerifyOTPRequest(BaseModel):
    email: EmailStr
    otp: str

class SignupRequest(BaseModel):
    username: Optional[str] = None
    email: EmailStr
    password: str = Field(..., min_length=8)
    confirm_password: str

class LoginRequest(BaseModel):
    email: EmailStr
    password: str

class UpdateYTAuthRequest(BaseModel):
    yt_auth_headers: str

class UpdateAppleRequest(BaseModel):
    apple_username: str
    apple_password: str

# Static Blocklist for disposable emails (example list)
DISPOSABLE_DOMAINS = {"mailinator.com", "10minutemail.com", "tempmail.com", "guerrillamail.com", "yopmail.com"}

def is_disposable_email(email: str) -> bool:
    domain = email.split('@')[-1].lower()
    return domain in DISPOSABLE_DOMAINS

def get_current_user(request: Request, db: Session = Depends(get_db)) -> User:
    # 1. Cookie authentication (Web Client)
    session_id = request.cookies.get("session_id")
    if session_id:
        db_session = db.query(DBSession).filter(DBSession.id == session_id).first()
        if db_session and db_session.expires_at >= datetime.now(timezone.utc):
            user = db.query(User).filter(User.id == db_session.user_id).first()
            if user:
                return user

    # 2. Bearer Token Authentication
    auth_header = request.headers.get("Authorization")
    if auth_header and auth_header.startswith("Bearer "):
        token = auth_header.split(" ")[1]
        db_session = db.query(DBSession).filter(DBSession.id == token).first()
        if db_session and db_session.expires_at >= datetime.now(timezone.utc):
            user = db.query(User).filter(User.id == db_session.user_id).first()
            if user:
                return user

    # 3. Mobile / Local App Direct Fallback:
    # Use existing user (or create default CacheTune user)
    default_user = db.query(User).first()
    if not default_user:
        default_user = User(
            email="cachetune@app.local",
            username="CacheTune Mobile User",
            password_hash=hash_password("cachetune_default_secret"),
            is_verified=True
        )
        db.add(default_user)
        db.commit()
        db.refresh(default_user)
    return default_user


@router.post("/send-otp")
def send_otp(req: SendOTPRequest, db: Session = Depends(get_db)):
    email = req.email.lower()
    if is_disposable_email(email):
        raise HTTPException(status_code=400, detail="Disposable email addresses are not allowed.")
    
    # Check if user already exists
    if db.query(User).filter(User.email == email).first():
        # Do not leak existence
        return {"message": "If the email is valid, an OTP has been sent.", "dev_otp": otp_code}
    
    # Rate limit check (e.g. 1 per minute)
    recent_otp = db.query(OTP).filter(OTP.email == email).order_by(OTP.created_at.desc()).first()
    if recent_otp:
        # Check if created within last 60 seconds
        now = datetime.now(timezone.utc)
        created = recent_otp.created_at
        if created.tzinfo is None:
            created = created.replace(tzinfo=timezone.utc)
        if (now - created).total_seconds() < 60:
            raise HTTPException(status_code=429, detail="Please wait before requesting another OTP.")

    # Invalidate old OTPs
    db.query(OTP).filter(OTP.email == email).delete()
    
    otp_code = generate_otp()
    hashed = hash_otp(otp_code)
    
    expires_at = datetime.now(timezone.utc) + timedelta(minutes=10)
    
    db_otp = OTP(email=email, otp_hash=hashed, expires_at=expires_at)
    db.add(db_otp)
    db.commit()
    
    send_otp_email(email, otp_code)
    
    return {"message": "If the email is valid, an OTP has been sent.", "dev_otp": otp_code}

@router.post("/verify-otp")
def verify_otp_endpoint(req: VerifyOTPRequest, response: Response, db: Session = Depends(get_db)):
    email = req.email.lower()
    db_otp = db.query(OTP).filter(OTP.email == email).first()
    
    if not db_otp:
        raise HTTPException(status_code=400, detail="Invalid or expired OTP.")
    
    now = datetime.now(timezone.utc)
    expires = db_otp.expires_at
    if expires.tzinfo is None:
        expires = expires.replace(tzinfo=timezone.utc)
    
    if now > expires:
        db.delete(db_otp)
        db.commit()
        raise HTTPException(status_code=400, detail="OTP has expired.")
        
    if db_otp.attempts >= 5:
        db.delete(db_otp)
        db.commit()
        raise HTTPException(status_code=400, detail="Too many failed attempts. Request a new OTP.")
        
    if not verify_otp(req.otp, db_otp.otp_hash):
        db_otp.attempts += 1
        db.commit()
        raise HTTPException(status_code=400, detail="Invalid OTP.")
        
    user = db.query(User).filter(User.email == email).first()
    if user:
        user.is_verified = True
        user.last_login = datetime.now(timezone.utc)
        
        # Create Session
        session_token = generate_session_token()
        expires_at = datetime.now(timezone.utc) + timedelta(days=30)
        db_session = DBSession(id=session_token, user_id=user.id, expires_at=expires_at)
        db.add(db_session)
        
        # Set Cookie
        response.set_cookie(
            key="session_id", value=session_token, httponly=True, secure=True, samesite="lax", max_age=30 * 24 * 60 * 60
        )
        
    db.delete(db_otp)
    db.commit()
    return {"message": "Email verified successfully. You are now logged in."}

@router.post("/signup")
def signup(req: SignupRequest, db: Session = Depends(get_db)):
    email = req.email.lower()
    if req.password != req.confirm_password:
        raise HTTPException(status_code=400, detail="Passwords do not match.")
    if not re.search(r"[A-Z]", req.password):
        raise HTTPException(status_code=400, detail="Password must contain at least one uppercase letter.")
    if not re.search(r"[!@#$%^&*(),.?\":{}|<>]", req.password):
        raise HTTPException(status_code=400, detail="Password must contain at least one symbol.")

        
    username = req.username.strip() if req.username and req.username.strip() else email.split('@')[0]
    existing_user = db.query(User).filter(User.email == email).first()
    if existing_user:
        if existing_user.is_verified:
            raise HTTPException(status_code=400, detail="User already exists.")
        else:
            # Re-send OTP for unverified user
            existing_user.username = username
            existing_user.password_hash = hash_password(req.password)
            db.commit()
    else:
        # Create unverified user
        user = User(
            username=username,
            email=email,
            password_hash=hash_password(req.password),
            is_verified=False
        )
        db.add(user)
        db.commit()
    
    # Generate and send OTP
    db.query(OTP).filter(OTP.email == email).delete()
    otp_code = generate_otp()
    hashed = hash_otp(otp_code)
    expires_at = datetime.now(timezone.utc) + timedelta(minutes=10)
    db_otp = OTP(username=username, email=email, otp_hash=hashed, expires_at=expires_at)
    db.add(db_otp)
    db.commit()
    send_otp_email(email, otp_code)
    
    return {"message": "Account created. Please verify your email.", "dev_otp": otp_code}

@router.post("/login")
def login(req: LoginRequest, response: Response, db: Session = Depends(get_db)):
    email = req.email.lower()
    user = db.query(User).filter(User.email == email).first()
    
    if not user or not verify_password(req.password, user.password_hash):
        raise HTTPException(status_code=401, detail="Invalid credentials.")
        
    # Update last login
    user.last_login = datetime.now(timezone.utc)
    
    # Create Session
    session_token = generate_session_token()
    expires_at = datetime.now(timezone.utc) + timedelta(days=30)
    
    db_session = DBSession(
        id=session_token,
        user_id=user.id,
        expires_at=expires_at
    )
    db.add(db_session)
    db.commit()
    
    # Set Cookie
    response.set_cookie(
        key="session_id",
        value=session_token,
        httponly=True,
        secure=False, # Allow HTTP for local network testing
        samesite="lax",
        max_age=30 * 24 * 60 * 60
    )
    
    return {"message": "Login successful"}

@router.post("/logout")
def logout(request: Request, response: Response, db: Session = Depends(get_db)):
    session_id = request.cookies.get("session_id")
    if session_id:
        db.query(DBSession).filter(DBSession.id == session_id).delete()
        db.commit()
    
    response.delete_cookie("session_id")
    return {"message": "Logged out successfully"}
