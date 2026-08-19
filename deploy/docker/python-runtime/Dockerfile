FROM python:3.11-slim

ARG RUNTIME_UID=10001
ARG RUNTIME_GID=10001

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    MPLCONFIGDIR=/tmp/matplotlib \
    TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

# ============================================================
# 系统基础组件
# ============================================================
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        wget \
        git \
        gcc \
        g++ \
        make \
        unixodbc \
        unixodbc-dev \
        libpq-dev \
        libsasl2-dev \
        libsasl2-modules \
        libkrb5-dev \
        krb5-user \
    && rm -rf /var/lib/apt/lists/*

# ============================================================
# 创建 Runtime 非 root 用户
# ============================================================
RUN groupadd --gid "${RUNTIME_GID}" runner \
    && useradd \
        --uid "${RUNTIME_UID}" \
        --gid "${RUNTIME_GID}" \
        --create-home \
        --shell /usr/sbin/nologin \
        runner

# ============================================================
# Python dependencies
# ============================================================
COPY requirements.lock /opt/chatchat/requirements.lock

RUN python -m pip install --upgrade pip setuptools wheel \
    && pip install \
        --no-cache-dir \
        --requirement /opt/chatchat/requirements.lock \
    && python -m pip check

# ============================================================
# Runtime directories
# ============================================================
RUN mkdir -p \
        /workspace \
        /data \
        /output \
        /tmp/matplotlib \
    && chown -R runner:runner \
        /workspace \
        /data \
        /output \
        /tmp/matplotlib

WORKDIR /workspace

USER runner:runner

CMD ["python"]