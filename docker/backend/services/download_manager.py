import threading
import time
import os
import subprocess
import re
from typing import Optional, Dict
from backend.db.database import SessionLocal
from backend.db.models import QueueEntry
from backend.services.downloader import download_song
from backend.services.converter import convert_to_flac, is_ffmpeg_available
from backend.services.cache_manager import CACHE_DIR, make_cache_basename, get_flac_path

class DownloadTask:
    def __init__(self, entry_id: str, session_id: str, position: int, apple_url: str, title: str, artist: str, user_id: int):
        self.entry_id = entry_id
        self.session_id = session_id
        self.position = position
        self.apple_url = apple_url
        self.title = title
        self.artist = artist
        self.user_id = user_id
        
        # State & Progress tracking
        self.phase = "pending" # pending, downloading, downloaded, decrypting, completed, cancelled, error
        self.progress_percent = 0.0
        self.is_running = False
        self.cancel_event = threading.Event()

class DownloadManager:
    _instance = None
    _lock = threading.Lock()

    def __new__(cls):
        with cls._lock:
            if cls._instance is None:
                cls._instance = super(DownloadManager, cls).__new__(cls)
                cls._instance._init()
            return cls._instance

    def _init(self):
        self.tasks: Dict[str, DownloadTask] = {}  # entry_id -> task
        self.current_position: int = 0
        self.current_session: str = ""
        
        self.active_task: Optional[DownloadTask] = None
        
        self.lock = threading.Lock()
        self.condition = threading.Condition(self.lock)
        
        self.worker_thread = threading.Thread(target=self._worker_loop, daemon=True)
        self.worker_thread.start()
        print("[DownloadManager] Started background priority worker thread")

    def get_task_phase(self, entry_id: str) -> tuple[str, float]:
        """Returns the real-time processing phase and progress percent for a queue entry."""
        with self.lock:
            if self.active_task and self.active_task.entry_id == entry_id:
                return self.active_task.phase, self.active_task.progress_percent
            if entry_id in self.tasks:
                t = self.tasks[entry_id]
                return t.phase, t.progress_percent
        return "pending", 0.0

    def update_state(self, session_id: str, current_position: int, next_entries: list, user_id: int):
        """
        Called whenever playback starts, songs skip, or queue is reordered.
        next_entries: list of dicts with id, position, apple_url, song_title, artist
        """
        with self.condition:
            self.current_session = session_id
            self.current_position = current_position
            
            active_entry_ids = set()
            for info in next_entries:
                eid = info["id"]
                active_entry_ids.add(eid)
                if eid in self.tasks:
                    # Update dynamic position in case queue was reordered
                    self.tasks[eid].position = info["position"]
                    if info.get("apple_url"):
                        self.tasks[eid].apple_url = info["apple_url"]
                else:
                    self.tasks[eid] = DownloadTask(
                        entry_id=eid,
                        session_id=session_id,
                        position=info["position"],
                        apple_url=info["apple_url"],
                        title=info["song_title"],
                        artist=info["artist"],
                        user_id=user_id
                    )
            
            # Remove tasks that are no longer part of active entries (and not currently running)
            to_remove = []
            for eid, task in self.tasks.items():
                if task.session_id == session_id and eid not in active_entry_ids:
                    if not task.is_running:
                        to_remove.append(eid)
                    
            for eid in to_remove:
                del self.tasks[eid]
            
            # Preemption Logic:
            # Only preempt if:
            # 1. The user explicitly clicked or jumped to a track at current_position that is NOT yet cached/completed, AND
            # 2. The active background task is downloading a different track.
            if self.active_task and self.active_task.is_running:
                if self.active_task.position != self.current_position:
                    # Check if current_position actually needs downloading
                    curr_task = next((t for t in self.tasks.values() if t.session_id == session_id and t.position == self.current_position), None)
                    curr_needs_download = curr_task is not None and curr_task.phase not in ("completed", "ready")
                    if curr_needs_download:
                        is_almost_done = (self.active_task.phase == "decrypting" and self.active_task.progress_percent >= 90.0)
                        if not is_almost_done:
                            print(f"[DownloadManager] Preempting '{self.active_task.title}' (pos {self.active_task.position}, {self.active_task.phase} {self.active_task.progress_percent:.0f}%) -> Allocating 100% bandwidth to current clicked track (pos {self.current_position})")
                            self.active_task.cancel_event.set()
            
            self.condition.notify()

    def _get_highest_priority_task(self) -> Optional[DownloadTask]:
        """
        Calculates strict priority:
        1. Clicked track at current_position (diff = 0) -> Priority 0 (FIRST)
        2. Immediate next track at current_position + 1 (diff = 1) -> Priority 1 (SECOND)
        Tracks further down (diff > 1) are held back until current track advances.
        """
        best_task = None
        best_priority = float('inf')
        
        for task in self.tasks.values():
            if task.session_id != self.current_session:
                continue
            if task.is_running or task.phase in ("completed", "cancelled"):
                continue
                
            diff = task.position - self.current_position
            # ONLY download the currently playing track (diff == 0) and the immediate next track (diff == 1)
            if diff == 0:
                priority = 0
            elif diff == 1:
                priority = 1
            else:
                # Do not download tracks further down the queue while the current track is playing
                continue
                
            if priority < best_priority:
                best_priority = priority
                best_task = task
                
        return best_task

    def _worker_loop(self):
        while True:
            task_to_run = None
            with self.condition:
                while True:
                    task_to_run = self._get_highest_priority_task()
                    if task_to_run:
                        task_to_run.is_running = True
                        self.active_task = task_to_run
                        break
                    else:
                        self.active_task = None
                        self.condition.wait()
            
            if task_to_run:
                try:
                    self._execute_download(task_to_run)
                finally:
                    with self.condition:
                        task_to_run.is_running = False
                        if task_to_run.entry_id in self.tasks:
                            del self.tasks[task_to_run.entry_id]
                        if self.active_task and self.active_task.entry_id == task_to_run.entry_id:
                            self.active_task = None

    def _execute_download(self, task: DownloadTask):
        priority_label = "1st TRACK (ACTIVE/PLAYING)" if task.position == self.current_position else f"TRACK POS +{task.position - self.current_position} (PRE-CACHE)"
        print(f"[DownloadManager] Priority Download Started [{priority_label}]: '{task.title}' by {task.artist}")
        print(f"[DownloadManager] Task Pos: {task.position} | Current Pos: {self.current_position}")
        
        ffmpeg_ok = is_ffmpeg_available()
        
        def _progress_cb(line: str):
            for part in line.split("\r"):
                part = part.strip()
                if not part:
                    continue
                if "Downloading..." in part:
                    task.phase = "downloading"
                    if "%" in part:
                        try:
                            pct_str = part.split("%")[0].split()[-1]
                            task.progress_percent = float(pct_str)
                        except Exception:
                            pass
                elif "Downloaded" in part:
                    task.phase = "downloaded"
                    task.progress_percent = 100.0
                elif "Decrypting..." in part:
                    task.phase = "decrypting"
                    if "%" in part:
                        try:
                            pct_str = part.split("%")[0].split()[-1]
                            task.progress_percent = float(pct_str)
                        except Exception:
                            pass
                elif "Decrypted" in part:
                    task.phase = "decrypted"
                    task.progress_percent = 100.0

        error_reason = "Download failed"
        try:
            with SessionLocal() as db:
                m4a_output = make_cache_basename(task.entry_id)
                m4a_path = download_song(
                    apple_url=task.apple_url,
                    dest_dir=CACHE_DIR,
                    db=db,
                    user_id=task.user_id,
                    output_filename=m4a_output,
                    progress_callback=_progress_cb,
                    cancel_event=task.cancel_event
                )
        except ValueError as ve:
            print(f"[DownloadManager] Configuration error for '{task.title}': {ve}")
            error_reason = str(ve)
            m4a_path = None
        except Exception as e:
            print(f"[DownloadManager] Exception for '{task.title}': {e}")
            error_reason = f"Download error: {e}"
            m4a_path = None
            
        if task.cancel_event.is_set():
            task.phase = "cancelled"
            print(f"[DownloadManager] Task '{task.title}' was successfully preempted/cancelled.")
            with SessionLocal() as db:
                entry = db.query(QueueEntry).filter(QueueEntry.id == task.entry_id).first()
                if entry and entry.status != "cached":
                    entry.status = "pending"
                    db.commit()
            return
            
        if not m4a_path or not os.path.exists(m4a_path):
            task.phase = "error"
            print(f"[DownloadManager] Download failed for '{task.title}': {error_reason}")
            with SessionLocal() as db:
                entry = db.query(QueueEntry).filter(QueueEntry.id == task.entry_id).first()
                if entry:
                    entry.status = "error"
                    entry.error_msg = error_reason
                    db.commit()
            return

        # Optimization & Container Flattening
        try:
            subprocess.run(["MP4Box", "-flat", m4a_path], capture_output=True, check=True)
        except Exception:
            pass

        stream_path = m4a_path

        # Transcode lossless ALAC to Studio FLAC
        task.phase = "converting"
        if ffmpeg_ok:
            flac_path = get_flac_path(m4a_path)
            converted = convert_to_flac(m4a_path, flac_path)
            if converted and os.path.exists(converted):
                stream_path = converted
        
        task.phase = "completed"
        task.progress_percent = 100.0
        
        with SessionLocal() as db:
            entry = db.query(QueueEntry).filter(QueueEntry.id == task.entry_id).first()
            if entry:
                entry.cache_path = stream_path
                entry.alac_path = m4a_path
                entry.status = "cached"
                db.commit()
        
        print(f"[DownloadManager] Completed [{priority_label}]: '{task.title}' -> {stream_path}")

download_manager = DownloadManager()
