"""
converter.py — ALAC (.m4a) → FLAC converter service.
Integrated into the streaming environment.

- Uses ffmpeg (must be in PATH)
- Converts in-place or to a separate output path
- Verifies ALAC codec before converting via ffprobe
"""

import os
import subprocess
from pathlib import Path
from typing import Optional


def is_alac(file_path: str) -> bool:
    """Return True if the audio codec of the file is ALAC."""
    cmd = [
        "ffprobe",
        "-v", "error",
        "-select_streams", "a:0",
        "-show_entries", "stream=codec_name",
        "-of", "default=noprint_wrappers=1:nokey=1",
        str(file_path),
    ]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        return result.stdout.strip().lower() == "alac"
    except Exception as e:
        print(f"[Converter] ffprobe error: {e}")
        return False


def convert_to_flac(input_path: str, output_path: Optional[str] = None) -> Optional[str]:
    """
    Convert an ALAC .m4a file to FLAC.

    Args:
        input_path: Full path to the ALAC .m4a file.
        output_path: Optional explicit output path. If not given,
                     the output will be placed next to the input
                     with a .flac extension.

    Returns:
        Path to the converted FLAC file, or None on failure.
    """
    input_path = Path(input_path)

    if not input_path.exists():
        print(f"[Converter] File not found: {input_path}")
        return None

    # Determine output path
    if output_path:
        out = Path(output_path)
    else:
        out = input_path.with_suffix(".flac")

    # Already exists — return immediately
    if out.exists():
        print(f"[Converter] FLAC already exists: {out}")
        return str(out)

    out.parent.mkdir(parents=True, exist_ok=True)

    cmd = [
        "ffmpeg",
        "-y",
        "-i", str(input_path),
        # Preserve all metadata including album art
        "-map_metadata", "0",
        "-map", "0",
        # FLAC audio, copy video stream (cover art)
        "-c:a", "flac",
        "-compression_level", "8",
        "-c:v", "copy",
        str(out),
    ]

    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=300,  # 5 minute timeout
        )

        if result.returncode == 0:
            print(f"[Converter] Converted: {out.name}")
            return str(out)
        else:
            print(f"[Converter] ffmpeg failed (exit {result.returncode}): {result.stderr[-500:]}")
            return None

    except subprocess.TimeoutExpired:
        print(f"[Converter] Conversion timed out for: {input_path.name}")
        return None
    except FileNotFoundError:
        print("[Converter] ffmpeg not found. Install ffmpeg and add it to PATH.")
        return None
    except Exception as e:
        print(f"[Converter] Exception during conversion: {e}")
        return None


def get_flac_path_for(m4a_path: str) -> str:
    """Return the expected FLAC path for a given .m4a path."""
    return str(Path(m4a_path).with_suffix(".flac"))


def is_ffmpeg_available() -> bool:
    """Check if ffmpeg is available in PATH."""
    try:
        subprocess.run(
            ["ffmpeg", "-version"],
            capture_output=True,
            timeout=5,
        )
        return True
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return False
