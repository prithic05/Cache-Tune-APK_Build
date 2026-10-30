from fastapi import FastAPI
from schemas import SongSearch
from scraper import search_song

app = FastAPI()

@app.post("/search")
def search(data: SongSearch):

    url = search_song(data.song_name)

    if url is None:
        return {
            "message": "Song not found"
        }

    return {
        "apple_music_url": url
    }