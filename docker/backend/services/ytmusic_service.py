"""
YT Music service — wraps ytmusicapi.

KEY FIX: The SAPISIDHASH inside the 'authorization' header expires every ~24h.
We recompute it fresh from the long-lived SAPISID cookie on every _get_yt_instance()
call. This means users only ever need to paste headers once — the cookie itself
lasts ~2 years, and we handle the short-lived hash automatically.
"""

import os
import sys
import json
import logging
from typing import List, Tuple, Optional, Any

logger = logging.getLogger(__name__)

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
YT_MUSIC_DIR = os.path.join(os.path.dirname(BASE_DIR), "yt-music")
BROWSER_JSON = os.path.join(YT_MUSIC_DIR, "browser.json")

# Add yt-music to path so we can import its logic
sys.path.insert(0, YT_MUSIC_DIR)

# Cache YTMusic instances keyed by user_id for connection reuse.
_instance_cache = {}


def clear_yt_instance_cache(user_id: int) -> None:
    """Evict all cached YTMusic instances for the given user_id."""
    keys_to_del = [k for k in list(_instance_cache.keys()) if str(k) == str(user_id) or str(k).startswith(f"{user_id}_")]
    for k in keys_to_del:
        _instance_cache.pop(k, None)


def clean_yt_cookie(cookie_str: str) -> str:
    """
    Ensure __Secure-3PAPISID is explicitly present for ytmusicapi internal parser.
    Preserves all cookies as given by the user.
    """
    if not cookie_str:
        return ""
    res = cookie_str.strip()
    sapisid, _ = _get_sapisid_and_origin({"cookie": res})
    if sapisid and "__Secure-3PAPISID" not in res:
        res += f"; __Secure-3PAPISID={sapisid}"
    return res


def _get_sapisid_and_origin(headers: dict) -> Tuple[Optional[str], str]:
    """
    Extract SAPISID (from __Secure-3PAPISID, __Secure-1PAPISID, SAPISID, or APISID cookie)
    and the request origin cleanly.
    """
    cookie_str = headers.get("cookie", "")
    origin = headers.get("origin") or headers.get("x-origin") or "https://music.youtube.com"
    sapisid = None

    if cookie_str:
        import re
        keys = ["__Secure-3PAPISID", "__Secure-1PAPISID", "SAPISID", "APISID"]
        for key in keys:
            match = re.search(r'(?:^|[\s;])' + re.escape(key) + r'\s*=\s*(?:\\"([^\\"]+)\\"|"([^"]+)"|([^;\s]+))', cookie_str)
            if match:
                val = match.group(1) or match.group(2) or match.group(3)
                if val:
                    sapisid = val.strip().strip('"')
                    break

    return sapisid, origin


def _compute_fresh_authorization(headers: dict) -> Optional[str]:
    """
    Compute SAPISIDHASH from the stored cookie string and origin.
    """
    try:
        sapisid, origin = _get_sapisid_and_origin(headers)
        if not sapisid:
            return None
        from ytmusicapi.helpers import get_authorization
        return get_authorization(f"{sapisid} {origin}")
    except Exception as e:
        logger.warning(f"[YTMusic] Could not compute SAPISIDHASH: {e}")
        return None


def _get_yt_instance(user_id: int, db):
    """
    Return a YTMusic instance for the given user directly from database credentials.
    Retrieves the user's saved configuration from the database as-is.
    """
    from ytmusicapi import YTMusic
    from backend.db.models import User
    from backend.utils.security import decrypt_data

    cache_key = str(user_id)
    if cache_key in _instance_cache:
        return _instance_cache[cache_key]

    user = db.query(User).filter(User.id == user_id).first()
    if not user or not user.yt_auth_headers:
        instance = YTMusic()
        _instance_cache[cache_key] = instance
        logger.info(f"[YTMusic] Using guest YTMusic client for user {user_id}")
        return instance

    try:
        headers_json = decrypt_data(user.yt_auth_headers)
    except Exception as e:
        logger.warning(f"[YTMusic] Invalid YouTube Music credentials for user {user_id}: {e}, falling back to guest client")
        instance = YTMusic()
        _instance_cache[cache_key] = instance
        return instance

    try:
        instance = YTMusic(auth=headers_json)
        _instance_cache[cache_key] = instance
        logger.info(f"[YTMusic] Instantiated YTMusic client for user {user_id}")
        return instance
    except Exception as e:
        logger.warning(f"[YTMusic] Failed to initialize authenticated client: {e}, falling back to guest client")
        instance = YTMusic()
        _instance_cache[cache_key] = instance
        return instance





def _extract_artist(item: Any, fallback: str = "Unknown Artist") -> str:
    """Safely extract artist name(s) from any ytmusic item structure."""
    if not isinstance(item, dict):
        return fallback
    artists = item.get("artists")
    if isinstance(artists, list) and len(artists) > 0:
        names = [a.get("name", "") for a in artists if isinstance(a, dict) and a.get("name")]
        if names:
            return ", ".join(names)
    author = item.get("author")
    if isinstance(author, str) and author.strip():
        return author.strip()
    elif isinstance(author, list) and len(author) > 0:
        names = [a.get("name", "") for a in author if isinstance(a, dict) and a.get("name")]
        if names:
            return ", ".join(names)
    subtitles = item.get("subtitles")
    if isinstance(subtitles, list) and len(subtitles) > 0:
        names = [s.get("name", "") for s in subtitles if isinstance(s, dict) and s.get("name")]
        if names:
            return ", ".join(names)
    return fallback


def get_best_thumbnail(thumbnails: Any) -> str:
    """Helper to extract the best quality thumbnail from ytmusicapi format safely."""
    if not thumbnails:
        return ""
    if isinstance(thumbnails, str):
        return thumbnails
    if isinstance(thumbnails, dict):
        return thumbnails.get("url", "")
    if isinstance(thumbnails, list) and len(thumbnails) > 0:
        valid_thumbs = [t for t in thumbnails if isinstance(t, dict) and t.get("url")]
        if not valid_thumbs:
            return ""
        try:
            sorted_thumbs = sorted(valid_thumbs, key=lambda x: x.get("width", 0), reverse=True)
            return sorted_thumbs[0].get("url", "")
        except Exception:
            return valid_thumbs[0].get("url", "")
    return ""


def get_home(db, user_id: int, limit: int = 3) -> List[dict]:
    """Get dynamic YouTube Music Home sections with bulletproof error handling."""
    home_data = []
    try:
        yt = _get_yt_instance(user_id, db)
        home_data = yt.get_home(limit=limit)
    except Exception as e:
        logger.warning(f"[get_home] Primary get_home failed ({e}). Retrying with limit=1...")
        try:
            yt = _get_yt_instance(user_id, db)
            home_data = yt.get_home(limit=1)
        except Exception as e2:
            logger.error(f"[get_home] Secondary get_home failed: {e2}")
            return []

    sections = []
    if not isinstance(home_data, list):
        return []

    for section in home_data:
        if not isinstance(section, dict):
            continue
        title = section.get("title", "")
        contents = section.get("contents", [])
        if not isinstance(contents, list):
            continue
        
        parsed_contents = []
        for item in contents:
            if not isinstance(item, dict):
                continue
            parsed_item = {
                "title": item.get("title", ""),
                "videoId": item.get("videoId"),
                "playlistId": item.get("playlistId"),
                "browseId": item.get("browseId"),
                "thumbnail": get_best_thumbnail(item.get("thumbnails") or item.get("thumbnail")),
                "description": item.get("description", ""),
                "artist": _extract_artist(item, fallback="")
            }
            parsed_contents.append(parsed_item)
            
        if parsed_contents:
            sections.append({
                "title": title or "Recommended",
                "contents": parsed_contents
            })
    return sections


def get_playlist(db, user_id: int, playlist_id: str) -> dict:
    """Get playlist details and tracks with robust fallback handling."""
    yt = _get_yt_instance(user_id, db)

    # 1. Album handling
    if playlist_id.startswith("MPRE"):
        try:
            album = yt.get_album(playlist_id)
            album_artist = _extract_artist(album, fallback=album.get("title", "Unknown Artist"))
            album_thumb = get_best_thumbnail(album.get("thumbnails") or album.get("thumbnail"))
            tracks = []
            for track in album.get("tracks", []):
                vid = track.get("videoId")
                if not vid:
                    continue
                tracks.append({
                    "videoId": vid,
                    "title": track.get("title", ""),
                    "artist": _extract_artist(track, fallback=album_artist),
                    "thumbnail": get_best_thumbnail(track.get("thumbnails") or track.get("thumbnail")) or album_thumb
                })
            return {
                "title": album.get("title", ""),
                "description": album.get("description", ""),
                "thumbnail": album_thumb,
                "tracks": tracks
            }
        except Exception as e:
            print(f"[get_playlist] Album parse error for {playlist_id}: {e}")

    # 2. Standard Playlist handling
    try:
        playlist = yt.get_playlist(playlist_id)
        playlist_thumb = get_best_thumbnail(playlist.get("thumbnails") or playlist.get("thumbnail"))
        tracks = []
        for track in playlist.get("tracks", []):
            vid = track.get("videoId")
            if not vid:
                continue
            tracks.append({
                "videoId": vid,
                "title": track.get("title", ""),
                "artist": _extract_artist(track),
                "thumbnail": get_best_thumbnail(track.get("thumbnails") or track.get("thumbnail")) or playlist_thumb
            })
        return {
            "title": playlist.get("title", ""),
            "description": playlist.get("description", ""),
            "thumbnail": playlist_thumb,
            "tracks": tracks
        }
    except Exception as e_pl:
        print(f"[get_playlist] Standard get_playlist failed for {playlist_id} ({e_pl}), trying watch_playlist...")

    # 3. Fallback: Watch Playlist (Radio / Mix / Station)
    try:
        wp = yt.get_watch_playlist(playlistId=playlist_id)
        tracks = []
        for t in wp.get("tracks", []):
            vid = t.get("videoId")
            if not vid:
                continue
            tracks.append({
                "videoId": vid,
                "title": t.get("title", ""),
                "artist": _extract_artist(t),
                "thumbnail": get_best_thumbnail(t.get("thumbnails") or t.get("thumbnail"))
            })
        if tracks:
            return {
                "title": "Radio Mix" if playlist_id.startswith("RD") else "Playlist",
                "description": "",
                "thumbnail": tracks[0]["thumbnail"] if tracks else "",
                "tracks": tracks
            }
    except Exception as e_wp:
        print(f"[get_playlist] watch_playlist failed for {playlist_id}: {e_wp}")

    # 4. Fallback: get_album
    try:
        album = yt.get_album(playlist_id)
        album_artist = _extract_artist(album, fallback=album.get("title", "Unknown Artist"))
        album_thumb = get_best_thumbnail(album.get("thumbnails") or album.get("thumbnail"))
        tracks = []
        for track in album.get("tracks", []):
            vid = track.get("videoId")
            if not vid:
                continue
            tracks.append({
                "videoId": vid,
                "title": track.get("title", ""),
                "artist": _extract_artist(track, fallback=album_artist),
                "thumbnail": get_best_thumbnail(track.get("thumbnails") or track.get("thumbnail")) or album_thumb
            })
        return {
            "title": album.get("title", ""),
            "description": album.get("description", ""),
            "thumbnail": album_thumb,
            "tracks": tracks
        }
    except Exception as e_alb:
        print(f"[get_playlist] Fallback album failed for {playlist_id}: {e_alb}")

    # 5. Fallback: Strip or add VL prefix
    if playlist_id.startswith("VL"):
        clean_id = playlist_id[2:]
        try:
            return get_playlist(db, user_id, clean_id)
        except Exception:
            pass

    raise RuntimeError(f"Could not load playlist or album with ID: {playlist_id}")


def search(db, user_id: int, query: str, limit: int = 15) -> List[dict]:
    """Standard search returning a list of songs."""
    yt = _get_yt_instance(user_id, db)
    results = yt.search(query=query, filter="songs", limit=limit)
    
    parsed_results = []
    for res in results:
        parsed_results.append({
            "videoId": res.get("videoId", ""),
            "title": res.get("title", ""),
            "artist": _extract_artist(res),
            "thumbnail": get_best_thumbnail(res.get("thumbnails") or res.get("thumbnail"))
        })
    return parsed_results


def search_and_queue(db, user_id: int, video_id: str = None, song: str = None, limit: int = 25) -> Tuple[str, str, str, List[dict]]:
    """
    Generate an autoplay/watch queue from a seed videoId or song query.
    Returns: (seed_title, seed_artist, seed_thumbnail, queue_list)
    """
    yt = _get_yt_instance(user_id, db)

    if not video_id and not song:
        raise ValueError("Must provide either video_id or song query")

    if not video_id:
        search_results = yt.search(query=song, filter="songs", limit=1)
        if not search_results:
            raise ValueError(f"No song found matching '{song}'")
        seed = search_results[0]
        video_id = seed.get("videoId", "")
        seed_title = seed.get("title", "Unknown")
        seed_artist = _extract_artist(seed)
        seed_thumbnail = get_best_thumbnail(seed.get("thumbnails") or seed.get("thumbnail"))
    else:
        seed_title = "Loading..."
        seed_artist = "Unknown Artist"
        seed_thumbnail = ""

    watch_playlist = yt.get_watch_playlist(
        videoId=video_id,
        limit=limit,
        radio=True
    )

    tracks = watch_playlist.get("tracks", [])
    queue = []
    
    if len(tracks) > 0 and seed_thumbnail == "":
        matching = next((t for t in tracks if t.get("videoId") == video_id), tracks[0])
        seed_title = matching.get("title", seed_title)
        seed_artist = _extract_artist(matching) if seed_artist == "Unknown Artist" else seed_artist
        seed_thumbnail = get_best_thumbnail(matching.get("thumbnails") or matching.get("thumbnail"))
    if not seed_thumbnail and video_id:
        seed_thumbnail = f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg"

    for t in tracks:
        if t.get("videoId") == video_id and len(queue) == 0 and t == tracks[0]:
            continue
            
        title = t.get("title", "")
        if title:
            queue.append({
                "title": title, 
                "artist": _extract_artist(t), 
                "videoId": t.get("videoId", ""),
                "thumbnail": get_best_thumbnail(t.get("thumbnails") or t.get("thumbnail"))
            })

    return seed_title, seed_artist, seed_thumbnail, queue


def is_auth_valid(user_id: int, db) -> bool:
    """
    Check if the YT Music auth is configured AND working.
    Performs a lightweight real API call (get_home limit=1 or search) to confirm.
    """
    from backend.db.models import User

    user = db.query(User).filter(User.id == user_id).first()
    if not user or not user.yt_auth_headers:
        return False

    try:
        yt = _get_yt_instance(user_id, db)
        try:
            yt.get_home(limit=1)
        except Exception:
            yt.search("test", limit=1)
        return True
    except Exception as e:
        logger.warning(f"[YTAuth] Auth check failed for user {user_id}: {e}")
        clear_yt_instance_cache(user_id)
        return False


def get_credentials_age_days(user_id: int, db) -> Optional[float]:
    """
    Return how many days ago the user last saved their YT Music headers.
    Returns None if headers have never been saved.
    """
    from datetime import datetime, timezone
    from backend.db.models import User

    user = db.query(User).filter(User.id == user_id).first()
    if not user or not user.yt_credentials_updated_at:
        return None

    updated_at = user.yt_credentials_updated_at
    if updated_at.tzinfo is None:
        updated_at = updated_at.replace(tzinfo=timezone.utc)

    delta = datetime.now(timezone.utc) - updated_at
    return round(delta.total_seconds() / 86400, 1)

