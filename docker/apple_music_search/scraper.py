"""
Improved Apple Music scraper.

Strategy:
  Apple Music's search page (https://music.apple.com/.../search) is a
  JavaScript-rendered SPA - a plain requests.get() only downloads the
  raw pre-JS HTML shell, so the actual "Top Search" result never appears
  in it and BeautifulSoup can't find it.

  Instead, we use Apple's own public iTunes Search API, which returns
  clean JSON with the exact song page URL - no HTML parsing needed.
"""

import requests
from bs4 import BeautifulSoup

BASE_URL = "https://itunes.apple.com/search"
BS4_SEARCH_URL = "https://music.apple.com/in/search"

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/122.0.0.0 Safari/537.36"
    ),
    "Accept-Language": "en-US,en;q=0.9",
}


import json
import re

import difflib

def normalize_text(text: str) -> str:
    if not text:
        return ""
    # Strip Apple Music movie suffix tags before lowercasing/removing punctuation
    text = re.sub(r'\(from\s+[^)]+\)', '', text, flags=re.IGNORECASE)
    text = re.sub(r'\[from\s+[^\]]+\]', '', text, flags=re.IGNORECASE)
    text = re.sub(r'\(original\s+motion\s+picture\s+soundtrack\)', '', text, flags=re.IGNORECASE)
    
    text = text.lower()
    text = text.replace("- single", "").replace("- ep", "")
    text = re.sub(r'[^\w\s]', '', text)
    text = re.sub(r'\s+', ' ', text)
    return text.strip()

def match_artist(yt_artist: str, am_artist: str) -> bool:
    yt_norm = normalize_text(yt_artist)
    if not yt_norm or not am_artist:
        return False
        
    am_parts = [normalize_text(p) for p in re.split(r',|&', am_artist)]
    for am_norm in am_parts:
        if not am_norm: 
            continue
        if yt_norm in am_norm or am_norm in yt_norm:
            return True
        if difflib.SequenceMatcher(None, yt_norm, am_norm).ratio() > 0.85:
            return True
            
    return False

def validate_url_with_requests(url: str, expected_title: str, expected_artist: str) -> bool:
    """Validate Apple Music URL by fetching the page and parsing the og:title metadata."""
    try:
        res = requests.get(url, headers=HEADERS, timeout=10)
        res.raise_for_status()
        soup = BeautifulSoup(res.text, "lxml")
        
        og_title_tag = soup.find("meta", property="og:title")
        if not og_title_tag or not og_title_tag.get("content"):
            return False
            
        content = og_title_tag["content"]
        if " by " in content and " on Apple" in content:
            # Strip off the " on Apple..." part
            content_no_apple = content.split(" on Apple")[0]
            parts = content_no_apple.rsplit(" by ", 1)
            am_title = parts[0].strip()
            am_artist = parts[-1].strip()
            
            title_match = normalize_text(expected_title) == normalize_text(am_title)
            artist_match = match_artist(expected_artist, am_artist)
            
            # Remove invisible Unicode marks for printing/logging to prevent stdout crashes
            safe_am_title = am_title.encode('ascii', 'ignore').decode('ascii')
            safe_am_artist = am_artist.encode('ascii', 'ignore').decode('ascii')
            
            print(f"[Validator] Target URL: {url}")
            print(f"[Validator] AM Title: '{safe_am_title}' | YT Title: '{expected_title}' | Match: {title_match}")
            print(f"[Validator] AM Artist: '{safe_am_artist}' | YT Artist: '{expected_artist}' | Match: {artist_match}")
            
            return title_match and artist_match
    except Exception as e:
        print(f"[Validator] Error validating URL {url}: {e}")
    return False

def extract_track_from_album(album_url: str) -> str | None:
    """Fetch an album/single page and extract the first song track URL containing ?i="""
    try:
        res = requests.get(album_url, headers=HEADERS, timeout=10)
        res.raise_for_status()
        soup = BeautifulSoup(res.text, "lxml")
        script = soup.find('script', id='serialized-server-data')
        if not script:
            return None
        data = json.loads(script.string)
        urls = []
        def find_urls(d):
            if isinstance(d, dict):
                if 'contentDescriptor' in d and d['contentDescriptor'].get('kind') == 'song':
                    url = d['contentDescriptor'].get('url', '')
                    if '?i=' in url:
                        urls.append(url)
                for k, v in d.items(): 
                    find_urls(v)
            elif isinstance(d, list):
                for v in d: 
                    find_urls(v)
        find_urls(data)
        return urls[0] if urls else None
    except Exception as e:
        print(f"[Scraper] Error extracting track from album {album_url}: {e}")
    return None

def _search_song_bs4(song_title: str, artist_name: str, country: str = "IN") -> str | None:
    """
    Fallback: scrape the Apple Music search page directly.
    Since it's a JS-rendered SPA, we extract the song URL from the 
    serialized JSON state, then validate it.
    """
    query = f"{song_title} {artist_name}".strip()
    bs4_search_url = f"https://music.apple.com/{country.lower()}/search"
    
    response = requests.get(
        bs4_search_url,
        params={"term": query},
        headers=HEADERS,
    )

    response.raise_for_status()
    soup = BeautifulSoup(response.text, "lxml")

    script = soup.find("script", id="serialized-server-data")
    if script and script.string:
        try:
            data = json.loads(script.string)
            if isinstance(data, list):
                sections = data[0].get('data', {}).get('sections', [])
            elif isinstance(data, dict):
                sections = data.get('data', [])[0].get('data', {}).get('sections', [])
            else:
                sections = []

            def get_song_url(item):
                url = None
                if 'contentDescriptor' in item:
                    url = item['contentDescriptor'].get('url', '')
                if not url and 'playAction' in item:
                    try:
                        url = item['playAction']['subactions'][0]['item'][0]['contentDescriptor']['url']
                    except:
                        pass
                
                if url:
                    if '?i=' in url:
                        return url
                    elif '/album/' in url:
                        print(f"[Scraper] Resolving track ID from Album URL: {url}")
                        return extract_track_from_album(url)
                return None

            for sec in sections:
                title = str(sec.get('header') or sec.get('title') or sec.get('id') or sec.get('item', {}).get('titleLink', {}).get('title', ''))
                if 'Top Results' in title or 'Songs' in title:
                    for item in sec.get('items', []):
                        url = get_song_url(item)
                        if url:
                            am_title = item.get('title', '')
                            subtitle_links = item.get('subtitleLinks', [])
                            am_artist = ", ".join([link.get('title', '') for link in subtitle_links])
                            
                            if not am_artist:
                                subtitle = item.get('subtitle', '')
                                if subtitle:
                                    parts = subtitle.split('·')
                                    am_artist = parts[-1].strip() if len(parts) > 1 else subtitle
                            
                            title_match = normalize_text(song_title) == normalize_text(am_title)
                            artist_match = match_artist(artist_name, am_artist)
                            
                            if title_match and artist_match:
                                print(f"[Scraper] Candidate found in BS4 JSON: {url}. Validating...")
                                is_valid = validate_url_with_requests(url, song_title, artist_name)
                                if is_valid:
                                    print(f"[Scraper] Validated exactly: {url}")
                                    return url
                                else:
                                    print(f"[Scraper] Validation failed for: {url}")
                                    
        except Exception as e:
            print(f"[Scraper] Error parsing JSON state: {e}")

    print(f"[Scraper] No exact Apple Music match found in BS4: Title: {song_title}, Artist: {artist_name}")
    return None

def _soft_search_song(song_title: str, country: str = "IN") -> str | None:
    """
    Final fallback: search iTunes API with JUST the song title (no artist).
    Returns the first result that is an exact title match.
    This resolves cases where YouTube Music returns a composer/lyricist 
    as the artist, but Apple Music only credits the singer.
    """
    if len(song_title) < 3:
        return None
    try:
        response = requests.get(
            BASE_URL,
            params={
                "term": song_title,
                "media": "music",
                "entity": "song",
                "limit": 5,
                "country": country,
            },
            headers=HEADERS,
            timeout=10,
        )
        response.raise_for_status()
        for res in response.json().get("results", []):
            am_title = res.get("trackName", "")
            href = res.get("trackViewUrl")
            if href and normalize_text(song_title) == normalize_text(am_title):
                print(f"[Scraper] Exact TITLE match (soft fallback) found via iTunes API: {href}")
                return href
    except Exception as e:
        print(f"[Scraper] Soft search failed: {e}")
    return None

def search_song(song_title: str, artist_name: str, country: str = "IN") -> str | None:
    """
    Search Apple Music (via the iTunes Search API) and return the URL
    for the exact matching song.
    """
    query = f"{song_title} {artist_name}".strip()
    try:
        response = requests.get(
            BASE_URL,
            params={
                "term": query,
                "media": "music",
                "entity": "song",
                "limit": 5,
                "country": country,
            },
            headers=HEADERS,
            timeout=15,
        )
        response.raise_for_status()
    except requests.RequestException as e:
        print(f"[Scraper] Request failed for '{query}': {e}")
        return _search_song_bs4(song_title, artist_name, country)

    results = response.json().get("results", [])

    for res in results:
        am_title = res.get("trackName", "")
        am_artist = res.get("artistName", "")
        href = res.get("trackViewUrl")
        
        if href:
            title_match = normalize_text(song_title) == normalize_text(am_title)
            artist_match = match_artist(artist_name, am_artist)
            if title_match and artist_match:
                print(f"[Scraper] Exact match found via iTunes API: {href}")
                return href

    print(f"[Scraper] No exact search match found via iTunes API for '{query}', falling back to bs4")
    
    url = _search_song_bs4(song_title, artist_name, country)
    if url:
        return url
        
    print(f"[Scraper] Exact match failed. Attempting soft fallback (Title only)...")
    return _soft_search_song(song_title, country)