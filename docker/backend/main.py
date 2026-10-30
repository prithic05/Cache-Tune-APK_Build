"""
FastAPI Orchestrator — Main application entry point.
Wires together: yt-music, apple_music_search, Go downloader, Docker wrapper.
Uses SQLite for session/cache state tracking.
"""

import os
import sys
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from backend.tasks.cleanup import start_cleanup_worker
from fastapi.staticfiles import StaticFiles

# Ensure backend package is importable
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from backend.routers import search, player, stream, download, config, home, playlist, auth, account, favorites, savedplaylists
from backend.db.database import Base, engine

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE_DIR = os.path.join(BASE_DIR, "cache")
DOWNLOADS_DIR = os.path.join(BASE_DIR, "downloads")


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Initialize DB and folders on startup."""
    os.makedirs(CACHE_DIR, exist_ok=True)
    os.makedirs(DOWNLOADS_DIR, exist_ok=True)
    
    # Auto-create tables & auto-migrate missing columns
    Base.metadata.create_all(bind=engine)
    from backend.db.database import ensure_schema_migrations
    ensure_schema_migrations(engine)
    print("[Backend] Database ready.")
    print(f"[Backend] Cache dir: {CACHE_DIR}")
    print(f"[Backend] Downloads dir: {DOWNLOADS_DIR}")
    
    # Start auto cleaner
    from backend.services.cache_manager import start_auto_cleaner
    start_auto_cleaner()
    
    yield
    # Cleanup on shutdown
    print("[Backend] Shutting down...")


app = FastAPI(
    title="Music Streaming Orchestrator",
    description="Full-stack music streaming: yt-music → apple_music_search → Go downloader",
    version="1.0.0",
    lifespan=lifespan,
)

# CORS — allow React dev server
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:3000",
        "http://127.0.0.1:3000",
        "http://localhost:5173",
        "http://127.0.0.1:5173",
        "http://localhost:5174",
        "http://127.0.0.1:5174",
    ],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Include routers
app.include_router(search.router, prefix="/api", tags=["search"])
app.include_router(player.router, prefix="/api", tags=["player"])
app.include_router(stream.router, prefix="/api", tags=["stream"])
app.include_router(download.router, prefix="/api", tags=["download"])
app.include_router(config.router, prefix="/api", tags=["config"])
app.include_router(home.router, prefix="/api", tags=["home"])
app.include_router(playlist.router, prefix="/api", tags=["playlist"])
app.include_router(auth.router)
app.include_router(account.router)
app.include_router(favorites.router)
app.include_router(savedplaylists.router)


@app.get("/")
def root():
    return {
        "service": "Music Streaming Orchestrator",
        "status": "running",
        "docs": "/docs"
    }


@app.get("/api/health")
def health():
    return {"status": "ok"}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
