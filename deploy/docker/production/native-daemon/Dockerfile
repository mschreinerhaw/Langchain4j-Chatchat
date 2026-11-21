ARG BASE_IMAGE=python:3.11-slim
FROM ${BASE_IMAGE}
COPY docker/ /usr/local/bin/
COPY supervise.py /usr/local/lib/runtime-os/supervise.py
RUN dockerd --version && containerd --version && runc --version
ENTRYPOINT ["python3", "/usr/local/lib/runtime-os/supervise.py"]
