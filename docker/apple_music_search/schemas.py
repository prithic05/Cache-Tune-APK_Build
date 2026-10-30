from pydantic import BaseModel

class SongSearch(BaseModel):
    song_name: str