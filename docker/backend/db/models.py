from sqlalchemy import Column, Integer, String, Boolean, DateTime, Text, ForeignKey, BigInteger
from sqlalchemy.orm import relationship
from sqlalchemy.sql import func
from backend.db.database import Base
import sqlalchemy as sa

class User(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, index=True)
    username = Column(String(100), nullable=True)
    email = Column(String(255), unique=True, index=True, nullable=False)
    password_hash = Column(String(255), nullable=False)
    is_verified = Column(Boolean, default=False)
    
    # Encrypted fields
    yt_auth_headers = Column(Text, nullable=True)
    apple_username = Column(Text, nullable=True)
    apple_password = Column(Text, nullable=True)
    
    # Timestamps
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), onupdate=func.now())
    last_login = Column(DateTime(timezone=True), nullable=True)
    yt_credentials_updated_at = Column(DateTime(timezone=True), nullable=True)
    apple_credentials_updated_at = Column(DateTime(timezone=True), nullable=True)

class OTP(Base):
    __tablename__ = "otps"
    
    id = Column(Integer, primary_key=True, index=True)
    username = Column(String(100), nullable=True)
    email = Column(String(255), unique=True, index=True, nullable=False)
    otp_hash = Column(String(255), nullable=False)
    expires_at = Column(DateTime(timezone=True), nullable=False)
    attempts = Column(Integer, default=0)
    created_at = Column(DateTime(timezone=True), server_default=func.now())

class Session(Base):
    """Server-side session mapping for the 30-day HttpOnly cookie"""
    __tablename__ = "sessions"
    
    id = Column(String(128), primary_key=True, index=True) # Secure random session token
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    expires_at = Column(DateTime(timezone=True), nullable=False)
    
    user = relationship("User")

class AppleWrapperSession(Base):
    """Tracks per-user Docker Compose projects for the Apple Music Wrapper"""
    __tablename__ = "apple_wrapper_sessions"
    
    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), unique=True, nullable=False)
    session_id = Column(String(128), unique=True, nullable=False) # e.g. apple_8f3a7c1e
    docker_project = Column(String(128), nullable=False)
    
    # Dynamic host ports assigned by Docker
    host_port_10020 = Column(Integer, nullable=True)
    host_port_20020 = Column(Integer, nullable=True)
    host_port_30020 = Column(Integer, nullable=True)
    
    status = Column(String(50), nullable=False, default="STARTING") # STARTING, READY, BUSY, FAILED
    error_message = Column(Text, nullable=True)
    
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    last_used_at = Column(DateTime(timezone=True), server_default=func.now())
    
    user = relationship("User")

class PlaybackSession(Base):
    __tablename__ = "playback_sessions"
    id = Column(String(128), primary_key=True)
    last_activity = Column(DateTime, server_default=func.now(), onupdate=func.now())

class QueueEntry(Base):
    __tablename__ = "queue"
    id = Column(String(128), primary_key=True)
    session_id = Column(String(128), index=True)
    position = Column(Integer)
    video_id = Column(String(128))
    title = Column(String(255))
    artist = Column(String(255))
    duration = Column(Integer)
    thumbnail = Column(String(1024))
    apple_url = Column(String(1024), nullable=True)
    status = Column(String(50))
    cache_path = Column(String(1024), nullable=True)
    alac_path = Column(String(1024), nullable=True)
    error_msg = Column(String(1024), nullable=True)

class AppConfig(Base):
    __tablename__ = "app_config"
    key = Column(String(128), primary_key=True)
    value = Column(Text, nullable=True)

class Favorite(Base):
    __tablename__ = "favorites"
    __table_args__ = (
        sa.UniqueConstraint('user_id', 'song_id', name='uq_favorites_user_song'),
    )
    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    song_id = Column(String(255), nullable=False)  # video_id / provider_song_id
    title = Column(String(500), nullable=True)
    artist = Column(String(500), nullable=True)
    thumbnail = Column(String(1024), nullable=True)
    provider = Column(String(50), nullable=True, default='youtube')
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    user = relationship("User")

class SavedPlaylist(Base):
    __tablename__ = "saved_playlists"
    __table_args__ = (
        sa.UniqueConstraint('user_id', 'playlist_id', name='uq_saved_playlists_user_playlist'),
    )
    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    playlist_id = Column(String(255), nullable=False)
    name = Column(String(500), nullable=True)
    description = Column(Text, nullable=True)
    thumbnail = Column(String(1024), nullable=True)
    provider = Column(String(50), nullable=True, default='youtube')
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    user = relationship("User")
