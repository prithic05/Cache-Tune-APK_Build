import os
import subprocess
import secrets
import time
import socket
from typing import Optional, Tuple
from datetime import datetime, timezone
from sqlalchemy.orm import Session
from sqlalchemy.exc import SQLAlchemyError

from backend.db.models import User, AppleWrapperSession
from backend.utils.security import decrypt_data

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
COMPOSE_FILE = os.path.join(BASE_DIR, "services", "apple-wrapper.yml")

def _probe_port(host: str, port: int, timeout: float = 1.0) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except Exception:
        return False

def _get_dynamic_port(project_name: str, private_port: int) -> Optional[int]:
    """Retrieve the host port assigned to a container's private port."""
    cmd = ["docker", "compose", "-p", project_name, "-f", COMPOSE_FILE, "port", "wrapper", str(private_port)]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, check=True)
        # Output is like: 0.0.0.0:32768
        output = result.stdout.strip()
        if ":" in output:
            return int(output.split(":")[-1])
    except Exception as e:
        print(f"Failed to get port for {project_name}:{private_port} - {e}")
    return None

def get_or_create_session(db: Session, user_id: int) -> AppleWrapperSession:
    """
    Ensures a wrapper session is running for the given user.
    Uses row-level locking (SELECT FOR UPDATE) to prevent race conditions.
    """
    # 0. Check if the shared wrapper container is already listening on port 10020
    if _probe_port("host.docker.internal", 10020):
        session_record = db.query(AppleWrapperSession).filter(AppleWrapperSession.user_id == user_id).first()
        if not session_record:
            session_record = AppleWrapperSession(
                user_id=user_id,
                session_id=f"apple_shared_{user_id}",
                docker_project="cachetune_wrapper",
                host_port_10020=10020,
                host_port_20020=20020,
                host_port_30020=30020,
                status="READY",
                last_used_at=datetime.now(timezone.utc)
            )
            db.add(session_record)
            db.commit()
            return session_record
        else:
            session_record.host_port_10020 = 10020
            session_record.host_port_20020 = 20020
            session_record.host_port_30020 = 30020
            session_record.status = "READY"
            session_record.last_used_at = datetime.now(timezone.utc)
            db.commit()
            return session_record

    # 1. Lock the user row to serialize wrapper creation for this specific user
    user = db.query(User).with_for_update().filter(User.id == user_id).first()
    if not user:
        raise ValueError("User not found")
        
    if not user.apple_username or not user.apple_password:
        raise ValueError("Apple credentials not configured and wrapper is not running")

    # 2. Check for existing session
    session_record = db.query(AppleWrapperSession).filter(AppleWrapperSession.user_id == user_id).first()
    
    if session_record:
        # Check if it's still alive
        if session_record.host_port_10020 and _probe_port("host.docker.internal", session_record.host_port_10020):
            session_record.last_used_at = datetime.now(timezone.utc)
            db.commit()
            return session_record
        else:
            # Stale or dead session, clean it up before recreating
            try:
                subprocess.run(["docker", "compose", "-p", session_record.docker_project, "-f", COMPOSE_FILE, "down", "-v"], check=False, capture_output=True)
            except Exception:
                pass
            db.delete(session_record)
            db.flush() # Force the DELETE to execute before the subsequent INSERT

    # 3. Create a new session
    token = secrets.token_hex(4)
    project_name = f"apple_{user_id}_{token}"
    
    new_session = AppleWrapperSession(
        user_id=user_id,
        session_id=project_name,
        docker_project=project_name,
        status="STARTING"
    )
    db.add(new_session)
    db.flush()
    db.refresh(new_session)
    
    # 4. Start the container
    try:
        apple_user = decrypt_data(user.apple_username)
        apple_pass = decrypt_data(user.apple_password)
        
        env = os.environ.copy()
        env["APPLE_USERNAME"] = apple_user
        env["APPLE_PASSWORD"] = apple_pass
        
        subprocess.run(
            ["docker", "compose", "-p", project_name, "-f", COMPOSE_FILE, "up", "-d"],
            env=env,
            check=True,
            capture_output=True
        )
        
        # 5. Discover ports
        port_10020 = _get_dynamic_port(project_name, 10020)
        port_20020 = _get_dynamic_port(project_name, 20020)
        port_30020 = _get_dynamic_port(project_name, 30020)
        
        if not all([port_10020, port_20020, port_30020]):
            raise RuntimeError("Failed to bind all required ports dynamically")
            
        new_session.host_port_10020 = port_10020
        new_session.host_port_20020 = port_20020
        new_session.host_port_30020 = port_30020
        
        # Wait for readiness
        ready = False
        for _ in range(15):
            if _probe_port("host.docker.internal", port_10020):
                ready = True
                break
            time.sleep(1)
            
        if not ready:
            raise RuntimeError("Wrapper container failed to become ready in time")
            
        new_session.status = "READY"
        new_session.last_used_at = datetime.now(timezone.utc)
        db.commit()
        return new_session
        
    except Exception as e:
        new_session.status = "FAILED"
        new_session.error_message = str(e)
        db.commit()
        # Attempt cleanup on failure
        subprocess.run(["docker", "compose", "-p", project_name, "-f", COMPOSE_FILE, "down", "-v"], check=False)
        raise RuntimeError("Failed to start Apple Wrapper") from e

def mark_session_busy(db: Session, session_id: int):
    record = db.query(AppleWrapperSession).filter(AppleWrapperSession.id == session_id).first()
    if record:
        record.status = "BUSY"
        record.last_used_at = datetime.now(timezone.utc)
        db.commit()

def mark_session_ready(db: Session, session_id: int):
    record = db.query(AppleWrapperSession).filter(AppleWrapperSession.id == session_id).first()
    if record:
        record.status = "READY"
        record.last_used_at = datetime.now(timezone.utc)
        db.commit()

def stop_session(db: Session, project_name: str):
    """Manually stop a specific session."""
    subprocess.run(["docker", "compose", "-p", project_name, "-f", COMPOSE_FILE, "down", "-v"], check=False)
    db.query(AppleWrapperSession).filter(AppleWrapperSession.docker_project == project_name).delete()
    db.commit()
