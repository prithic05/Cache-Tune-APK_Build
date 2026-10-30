"""
Downloader service ?" runs the Go-based apple-music-downloader.
Produces ALAC .m4a files (lossless), then auto-converts to FLAC for browser playback.

URL Type Handling:
  The Go downloader routes by URL pattern:
    /album/   +' album download (with ?i= for single-song)
    /song/    +' song download (redirects to album internally)
    /playlist/ +' playlist
    /station/  +' station
    /music-video/ +' MV

  Apple Music search scraper returns different URL types depending on result.
  We normalise here: if the URL has ?i= we pass it as-is (album+song).
"""

import os
import uuid
import subprocess
import shutil
import json
import yaml
import threading
from typing import Optional, Callable
from sqlalchemy.orm import Session

from backend.services.apple_wrapper_manager import get_or_create_session, mark_session_busy, mark_session_ready
from backend.db.database import _SessionFactory # Or we can pass the session directly

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOWNLOADER_DIR = os.path.join(os.path.dirname(BASE_DIR), "Downloader", "apple-music-downloader")
CACHE_DIR = os.path.join(os.path.dirname(BASE_DIR), "cache")
DOWNLOADS_DIR = os.path.join(os.path.dirname(BASE_DIR), "downloads")
TEMP_SESSIONS_DIR = os.path.join(os.path.dirname(BASE_DIR), "Downloader", "temp_sessions")

# Patterns the Go downloader recognises as valid URLs
VALID_URL_PATTERNS = ["/album/", "/song/", "/playlist/", "/station/", "/music-video/"]


def is_valid_apple_url(url: str) -> bool:
    """Return True if this URL matches a pattern the Go downloader understands."""
    return any(p in url for p in VALID_URL_PATTERNS)


def normalise_apple_url(url: str) -> str:
    """
    If the Apple Music scraper returns a URL without a recognised path segment
    (e.g., bare https://music.apple.com/in/<id>), try to convert it.
    Most commonly, the scraper returns /album/ URLs with ?i= for single songs.
    """
    if not url:
        return url
    url = url.strip()
    if is_valid_apple_url(url):
        return url
    print(f"[Downloader] Warning: URL does not match expected patterns: {url}")
    return url

def _ensure_amd_binary_built():
    """Ensure the Go binary is compiled so we can run it concurrently from any folder."""
    binary_path = os.path.join(DOWNLOADER_DIR, "amd")
    if not os.path.exists(binary_path):
        print(f"[Downloader] Compiling apple-music-downloader to {binary_path}...")
        subprocess.run(["go", "build", "-buildvcs=false", "-o", "amd"], cwd=DOWNLOADER_DIR, check=True)
        try:
            os.chmod(binary_path, 0o755)
        except Exception:
            pass
    return binary_path

def download_song(
    apple_url: str,
    dest_dir: str,
    db: Session,
    user_id: int,
    output_filename: Optional[str] = None,
    progress_callback: Optional[Callable[[str], None]] = None,
    cancel_event: Optional[threading.Event] = None
) -> Optional[str]:
    """
    Download a song via the Go downloader using an isolated user session and temporary config.
    """
    os.makedirs(dest_dir, exist_ok=True)
    os.makedirs(TEMP_SESSIONS_DIR, exist_ok=True)

    url = normalise_apple_url(apple_url)
    if not is_valid_apple_url(url):
        print(f"[Downloader] Invalid Apple Music URL format: {url!r}")
        return None
        
    binary_path = _ensure_amd_binary_built()

    wrapper_session = None
    session_cwd = None

    # 1. Fetch active session and dynamic ports
    try:
        wrapper_session = get_or_create_session(db, user_id)
    except Exception as e:
        print(f"[Downloader] Apple wrapper session unavailable for user {user_id}: {e}")
        raise ValueError(f"Apple Music wrapper unavailable: {e}")

    session_id = wrapper_session.session_id
    port_10020 = wrapper_session.host_port_10020
    port_20020 = wrapper_session.host_port_20020
    
    # 2. Mark session as busy
    try:
        mark_session_busy(db, wrapper_session.id)
    except Exception as e:
        print(f"[Downloader] Could not mark session busy: {e}")
    
    try:
        # 3. Setup isolated config environment with a UNIQUE execution dir
        exec_id = f"{session_id}_{uuid.uuid4().hex[:8]}"
        session_cwd = os.path.join(TEMP_SESSIONS_DIR, exec_id)
        os.makedirs(session_cwd, exist_ok=True)
        
        base_config_path = os.path.join(DOWNLOADER_DIR, "config.yaml")
        session_config_path = os.path.join(session_cwd, "config.yaml")
        
        with open(base_config_path, "r", encoding="utf-8") as f:
            config_content = f.read()
            
        # Determine wrapper hostname: use direct bridge hostname 'wrapper' if reachable
        import socket, re
        wrapper_target_host = "wrapper"
        try:
            with socket.create_connection(("wrapper", 10020), timeout=0.3):
                wrapper_target_host = "wrapper"
        except Exception:
            wrapper_target_host = "host.docker.internal"

        print(f"[Downloader] Connecting to wrapper on: {wrapper_target_host}:{port_10020}")
        config_content = re.sub(r'decrypt-m3u8-port:.*', f'decrypt-m3u8-port: "{wrapper_target_host}:{port_10020}"', config_content)
        config_content = re.sub(r'get-m3u8-port:.*', f'get-m3u8-port: "{wrapper_target_host}:{port_20020}"', config_content)
        
        with open(session_config_path, "w", encoding="utf-8") as f:
            f.write(config_content)
            
        # 4. Execute downloader
        has_track_param = "?i=" in url or "&i=" in url
        cmd = [binary_path, "--json"]
        if has_track_param:
            cmd.append("--song")
        cmd.append(url)

        print(f"[Downloader] CMD: {' '.join(cmd)}")
        print(f"[Downloader] CWD: {session_cwd}")

        process = subprocess.Popen(
            cmd,
            cwd=session_cwd,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
        )

        output_lines = []
        try:
            for line in process.stdout:
                if cancel_event and cancel_event.is_set():
                    print(f"[Downloader] Cancellation requested. Terminating process.")
                    process.terminate()
                    break
                    
                line = line.rstrip()
                output_lines.append(line)
                if progress_callback:
                    progress_callback(line)
                try:
                    print(f"[Downloader] {line}")
                except UnicodeEncodeError:
                    print(f"[Downloader] {line.encode('ascii', 'replace').decode('ascii')}")

            process.wait(timeout=120)
        except subprocess.TimeoutExpired:
            print("[Downloader] Timeout expired (120s) waiting for Go downloader process.")
            process.kill()
            return None

        if process.returncode != 0:
            print(f"[Downloader] Process exited with code {process.returncode}")

        # Parse JSON from the output to find the exact file path
        new_file = None
        for line in reversed(output_lines):
            line = line.strip()
            if line.startswith("[{") and line.endswith("}]"):
                try:
                    data = json.loads(line)
                    if data and isinstance(data, list) and len(data) > 0 and "path" in data[0]:
                        candidate = os.path.join(session_cwd, data[0]["path"])
                        if os.path.exists(candidate):
                            new_file = candidate
                            break
                except json.JSONDecodeError:
                    pass

        # Filesystem fallback: scan session_cwd for any downloaded audio file
        if not new_file or not os.path.exists(new_file):
            print("[Downloader] JSON path not found, scanning session folder for audio files...")
            candidates = []
            for root, _, files in os.walk(session_cwd):
                for f in files:
                    if f.endswith((".m4a", ".flac")) and not f.startswith("."):
                        f_path = os.path.join(root, f)
                        try:
                            if os.path.getsize(f_path) > 100000:
                                candidates.append(f_path)
                        except Exception:
                            pass
            if candidates:
                candidates.sort(key=lambda p: os.path.getsize(p), reverse=True)
                new_file = candidates[0]
                print(f"[Downloader] Located audio file via filesystem fallback: {new_file}")

        if not new_file or not os.path.exists(new_file):
            print("[Downloader] Could not locate downloaded file in JSON output or filesystem.")
            return None

        # Build destination filename
        ext = os.path.splitext(new_file)[1]  # .m4a
        if output_filename:
            base_name = output_filename
            if not base_name.endswith(ext):
                base_name = os.path.splitext(base_name)[0] + ext
        else:
            base_name = os.path.basename(new_file)

        dest_path = os.path.join(dest_dir, base_name)
        os.makedirs(os.path.dirname(dest_path), exist_ok=True)
        
        # Move it to the destination (or copy if we want to cache)
        shutil.move(new_file, dest_path)
        print(f"[Downloader] Moved to: {dest_path}")
        
        return dest_path

    except Exception as e:
        print(f"[Downloader] Exception: {e}")
        return None
    finally:
        # Cleanup session_cwd temp folder
        if session_cwd and os.path.exists(session_cwd):
            try:
                shutil.rmtree(session_cwd, ignore_errors=True)
            except Exception:
                pass

        # 5. Revert session status back to READY
        if wrapper_session:
            try:
                mark_session_ready(db, wrapper_session.id)
            except Exception as e:
                print(f"[Downloader] Error marking session ready: {e}")
