FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY server/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir --requirement requirements.txt \
 && useradd --system --uid 10001 --no-create-home akzsa
COPY server/app ./app
USER akzsa
EXPOSE 8000
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}"]
