# Test-only reader image; production keeps its already published, verified Sandbox image.
FROM python:3.11.16-slim-bookworm
COPY format-verification.requirements.lock /opt/verification.requirements.lock
RUN pip install --no-cache-dir -r /opt/verification.requirements.lock && pip check
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
USER 10001:10001
CMD ["python"]
