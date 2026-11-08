# Backend + dashboard in one container. Build from the repo root:
#   docker build -t smart-eyes .  &&  docker run -d -p 80:8000 --env-file backend/.env smart-eyes
FROM python:3.12-slim
WORKDIR /srv
COPY backend/requirements.txt backend/requirements.txt
RUN pip install --no-cache-dir -r backend/requirements.txt
COPY backend backend
COPY dashboard dashboard
WORKDIR /srv/backend
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers"]
