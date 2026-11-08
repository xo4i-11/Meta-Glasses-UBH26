import os
from pathlib import Path

from dotenv import load_dotenv

BACKEND_DIR = Path(__file__).resolve().parent.parent
load_dotenv(BACKEND_DIR / ".env")

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "").strip()
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-3.6-flash").strip()
GEMINI_THINKING_BUDGET = int(os.getenv("GEMINI_THINKING_BUDGET", "0"))
# Tried in order when the main model is busy (503/429) or unavailable (404)
GEMINI_FALLBACK_MODELS = [m.strip() for m in os.getenv("GEMINI_FALLBACK_MODELS", "gemini-3.5-flash-lite,gemini-3.1-flash-lite,gemini-3.8-flash").split(",") if m.strip()]

ELEVENLABS_API_KEY = os.getenv("ELEVENLABS_API_KEY", "").strip()
ELEVENLABS_VOICE_ID = os.getenv("ELEVENLABS_VOICE_ID", "21m00Tcm4TlvDq8ikWAM").strip()
ELEVENLABS_MODEL = os.getenv("ELEVENLABS_MODEL", "eleven_flash_v2_5").strip()

DATABASE_URL = os.getenv("DATABASE_URL", "").strip()
SQLITE_PATH = BACKEND_DIR / "smart_eyes.db"
DB_TABLE_PREFIX = os.getenv("DB_TABLE_PREFIX", "smarteyes_").strip()

APP_TOKEN = os.getenv("APP_TOKEN", "").strip()

DASHBOARD_DIR = BACKEND_DIR.parent / "dashboard"
