FROM python:3.11-slim-bookworm

ARG RUNTIME_UID=10001
ARG RUNTIME_GID=10001

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    MPLCONFIGDIR=/tmp/matplotlib

RUN groupadd --gid "${RUNTIME_GID}" runner \
    && useradd --uid "${RUNTIME_UID}" --gid "${RUNTIME_GID}" --create-home --shell /usr/sbin/nologin runner

COPY requirements.lock /opt/chatchat/requirements.lock
RUN pip install --no-cache-dir --requirement /opt/chatchat/requirements.lock \
    && python -m pip check

WORKDIR /workspace
USER runner:runner

CMD ["python"]
