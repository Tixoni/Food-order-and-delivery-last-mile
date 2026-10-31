# Сервис доставки на Python — замена образа apps/delivery исходного проекта.
# Контекст сборки — папка delivery-py.

FROM python:3.12-slim

WORKDIR /app
ENV PYTHONUNBUFFERED=1 PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY delivery ./delivery

RUN useradd --system --uid 1001 app
USER app

EXPOSE 3004

HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=5 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:3004/health', timeout=2).status == 200 else 1)"

CMD ["python", "-m", "delivery"]
