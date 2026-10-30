"""
Docker service — manages the Apple Music wrapper container.
Injects username/password into the Docker run command at runtime.
"""

import os
import subprocess
import threading
import socket
import time
from typing import Optional, Tuple

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WRAPPER_DIR = os.path.join(os.path.dirname(BASE_DIR), "Downloader", "wrapper")
ROOTFS_DATA = os.path.join(WRAPPER_DIR, "rootfs", "data")

# In-memory state
_wrapper_process: Optional[subprocess.Popen] = None
_wrapper_thread: Optional[threading.Thread] = None
_wrapper_status = {"running": False, "message": "Not started", "container_id": None}


def get_status() -> dict:
    """Return current wrapper status."""
    # Also probe port 10020 to verify it's truly alive
    alive = _probe_port("host.docker.internal", 10020)
    _wrapper_status["running"] = alive
    if alive:
        _wrapper_status["message"] = "Wrapper running on port 10020"
    return _wrapper_status.copy()


def _probe_port(host: str, port: int, timeout: float = 1.0) -> bool:
    """Check if a port is open."""
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except Exception:
        return False


def build_docker_image(progress_callback=None) -> Tuple[bool, str]:
    """
    Build the wrapper Docker image.
    docker build --tag ghcr.io/worldobservationlog/wrapper:local .
    """
    cmd = ["docker", "build", "--tag", "ghcr.io/worldobservationlog/wrapper:local", "."]
    try:
        process = subprocess.Popen(
            cmd,
            cwd=WRAPPER_DIR,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace"
        )
        output_lines = []
        for line in process.stdout:
            line = line.rstrip()
            output_lines.append(line)
            if progress_callback:
                progress_callback(line)
            print(f"[DockerBuild] {line}")

        process.wait()
        if process.returncode == 0:
            return True, "Docker image built successfully."
        else:
            return False, "\n".join(output_lines[-10:])
    except FileNotFoundError:
        return False, "Docker not found. Ensure Docker is installed and running."
    except Exception as e:
        return False, str(e)


def start_wrapper(username: str, password: str) -> Tuple[bool, str]:
    """
    Start the Apple Music wrapper container with injected credentials.
    Returns (success, message).
    """
    global _wrapper_process, _wrapper_status

    # Stop existing wrapper if running
    stop_wrapper()

    os.makedirs(ROOTFS_DATA, exist_ok=True)

    # Construct the docker command with credentials injected via -L flag
    cmd = [
        "docker", "run", "--privileged", "--rm",
        "-p", "10020:10020",
        "-p", "20020:20020",
        "-p", "30020:30020",
        "-v", f"{ROOTFS_DATA}:/app/rootfs/data",
        "--entrypoint", "./wrapper",
        "ghcr.io/worldobservationlog/wrapper:local",
        "-L", f"{username}:{password}",
        "-H", "0.0.0.0"
    ]

    try:
        _wrapper_process = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace"
        )
        _wrapper_status["running"] = True
        _wrapper_status["message"] = "Wrapper starting..."

        # Monitor in background
        def _monitor():
            for line in _wrapper_process.stdout:
                print(f"[Wrapper] {line.rstrip()}")
            _wrapper_status["running"] = False
            _wrapper_status["message"] = "Wrapper stopped"

        _wrapper_thread = threading.Thread(target=_monitor, daemon=True)
        _wrapper_thread.start()

        # Wait a moment and probe
        time.sleep(3)
        alive = _probe_port("host.docker.internal", 10020)
        _wrapper_status["running"] = alive
        _wrapper_status["message"] = "Wrapper running" if alive else "Wrapper started (port not yet open)"


        return True, _wrapper_status["message"]

    except FileNotFoundError:
        _wrapper_status["running"] = False
        _wrapper_status["message"] = "Docker not found"
        return False, "Docker not found. Ensure Docker Desktop is running."
    except Exception as e:
        _wrapper_status["running"] = False
        _wrapper_status["message"] = str(e)
        return False, str(e)


def stop_wrapper():
    """Terminate the wrapper container."""
    global _wrapper_process
    if _wrapper_process:
        try:
            _wrapper_process.terminate()
            _wrapper_process.wait(timeout=5)
        except Exception:
            pass
        _wrapper_process = None
    _wrapper_status["running"] = False
    _wrapper_status["message"] = "Wrapper stopped"
