import os
from sqlalchemy import create_engine
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker
from dotenv import load_dotenv

load_dotenv()

DATABASE_URL = os.getenv(
    "DATABASE_URL", 
    "postgresql+psycopg2://cachetune:cachetune@localhost:5432/cachetune_db"
)

engine = create_engine(
    DATABASE_URL,
    echo=False
)

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
_SessionFactory = SessionLocal
Base = declarative_base()

def ensure_schema_migrations(engine):
    """Auto-migrate missing columns in PostgreSQL database tables."""
    from sqlalchemy import inspect, text
    try:
        inspector = inspect(engine)
        tables = inspector.get_table_names()
        
        # Check 'otps' table
        if 'otps' in tables:
            columns = [c['name'] for c in inspector.get_columns('otps')]
            with engine.connect() as conn:
                if 'username' not in columns:
                    conn.execute(text("ALTER TABLE otps ADD COLUMN username VARCHAR(100);"))
                    conn.commit()
                    print("[Migration] Added missing 'username' column to 'otps' table.")

        # Check 'users' table
        if 'users' in tables:
            columns = [c['name'] for c in inspector.get_columns('users')]
            with engine.connect() as conn:
                if 'username' not in columns:
                    conn.execute(text("ALTER TABLE users ADD COLUMN username VARCHAR(100);"))
                    conn.commit()
                if 'yt_credentials_updated_at' not in columns:
                    conn.execute(text("ALTER TABLE users ADD COLUMN yt_credentials_updated_at TIMESTAMP WITH TIME ZONE;"))
                    conn.commit()
                if 'apple_credentials_updated_at' not in columns:
                    conn.execute(text("ALTER TABLE users ADD COLUMN apple_credentials_updated_at TIMESTAMP WITH TIME ZONE;"))
                    conn.commit()

    except Exception as e:
        print(f"[Migration] Schema migration notice: {e}")


def get_db():
    db = _SessionFactory()
    try:
        yield db
    finally:
        db.close()
