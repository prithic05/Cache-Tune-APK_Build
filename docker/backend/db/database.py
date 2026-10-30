import os
from sqlalchemy import create_engine
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker
from dotenv import load_dotenv

load_dotenv()

DATABASE_URL = os.getenv(
    "DATABASE_URL", 
    "sqlite:///./backend/cachetune.db"
)

connect_args = {"check_same_thread": False} if DATABASE_URL.startswith("sqlite") else {}

engine = create_engine(
    DATABASE_URL,
    connect_args=connect_args,
    echo=False
)

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
_SessionFactory = SessionLocal
Base = declarative_base()

def ensure_schema_migrations(engine):
    """Schema ready."""
    pass

def get_db():
    db = _SessionFactory()
    try:
        yield db
    finally:
        db.close()
