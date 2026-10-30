from fastapi import FastAPI, Query, HTTPException
from pydantic import BaseModel
from typing import List

from ytmusicapi import YTMusic

app = FastAPI(
    title="YouTube Music Personalized Recommendation API",
    version="1.0.0",
    description="Returns your personalized YouTube Music home recommendations "
                 "and autoplay/radio-style recommendations for a searched song."
)

# Browser authentication
yt = YTMusic("browser.json")


class HomeSection(BaseModel):
    section: str
    songs: List[str]


class AutoplayResponse(BaseModel):
    seed_song: str
    autoplay_queue: List[str]


@app.get("/")
def home():
    return {
        "message": "YouTube Music Personalized Recommendation API",
        "endpoints": ["/home", "/autoplay"]
    }


@app.get("/home", response_model=List[HomeSection])
def get_home():

    home = yt.get_home()

    response = []

    for section in home:

        section_title = section.get("title", "Unknown")

        titles = []

        for item in section.get("contents", []):

            title = item.get("title")

            if title:
                titles.append(title)

        if titles:
            response.append(
                HomeSection(
                    section=section_title,
                    songs=titles
                )
            )

    return response


@app.get("/autoplay", response_model=AutoplayResponse)
def get_autoplay(
    song: str = Query(..., description="Song name to search and start 'playing'"),
    limit: int = Query(25, description="Number of tracks to return in the autoplay queue"),
    radio: bool = Query(False, description="True = pure radio mix, False = normal autoplay 'up next' queue")
):
    """
    Mimics what happens when you press play on a song in YouTube Music:
    finds the song, then pulls the auto-generated 'up next' / radio queue
    that YT Music itself would queue up (get_watch_playlist).
    """

    # Step 1: find the song the user typed
    search_results = yt.search(query=song, filter="songs", limit=1)

    if not search_results:
        raise HTTPException(status_code=404, detail=f"No song found matching '{song}'")

    seed = search_results[0]
    seed_video_id = seed["videoId"]
    seed_title = seed["title"]

    # Step 2: ask YT Music for the watch/autoplay queue for that song
    # (this is the exact same call YT Music makes when you hit play)
    watch_playlist = yt.get_watch_playlist(
        videoId=seed_video_id,
        limit=limit,
        radio=radio
    )

    tracks = watch_playlist.get("tracks", [])

    queue = []
    for t in tracks:
        # skip the seed track itself if YT Music includes it first
        if t.get("videoId") == seed_video_id:
            continue

        title = t.get("title")
        if title:
            queue.append(title)

    return AutoplayResponse(
        seed_song=seed_title,
        autoplay_queue=queue
    )