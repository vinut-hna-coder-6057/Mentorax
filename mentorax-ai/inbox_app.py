"""Optional production entry point that adds inbox assistance to the existing AI app."""
from app import app
from inbox_assistant import router

app.include_router(router)
