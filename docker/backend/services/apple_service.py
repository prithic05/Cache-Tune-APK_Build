"""
Apple Music search service — wraps apple_music_search/scraper.py.
"""

import os
import sys
from typing import Optional

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APPLE_SEARCH_DIR = os.path.join(os.path.dirname(BASE_DIR), "apple_music_search")

# Add apple_music_search to path
sys.path.insert(0, APPLE_SEARCH_DIR)


def resolve_apple_url(song_title: str, artist: str = "") -> Optional[str]:
    """
    Resolve a song title to an Apple Music URL.
    Returns URL string or None if not found.
    """
    try:
        from scraper import search_song
        
        # Clean the artist string to just the primary artist.
        # YT Music often returns "Artist1, Artist2 & Artist3"
        # which completely breaks Apple Music's search algorithm.
        if artist:
            primary_artist = artist.split(',')[0].split('&')[0].strip()
        else:
            primary_artist = ""
            
        print(f"[AppleService] Resolving query: Title: '{song_title}', Artist: '{primary_artist}'")
        url = search_song(song_title, primary_artist)
        return url
    except Exception as e:
        print(f"[AppleService] Error resolving '{song_title}': {e}")
        return None
