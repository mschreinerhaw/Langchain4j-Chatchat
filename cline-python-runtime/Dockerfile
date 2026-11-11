# Build using existing remote artifacts; no Java, Runtime gateway or model SDK patch.
ARG PYTHON_BASE=chatchat-python-runtime:3.11-v1.2-fonts-r2-20261022
FROM ${PYTHON_BASE}
USER root
COPY cline/ /srv/cline/
COPY entrypoint.py /srv/bin/entrypoint.py
RUN chmod 0755 /srv/cline/bin/cline \
    && ln -s /srv/cline/bin/cline /usr/local/bin/cline \
    && mkdir -p /srv/workspace /srv/state /srv/cache /srv/matplotlib \
    && chown runner:runner /srv/workspace /srv/state /srv/cache /srv/matplotlib
ENV HOME=/srv/state PYTHONUNBUFFERED=1 PYTHONIOENCODING=utf-8 \
    PIP_CACHE_DIR=/srv/cache/pip MPLCONFIGDIR=/srv/matplotlib MPLBACKEND=Agg
WORKDIR /srv/workspace
USER runner:runner
RUN python -c "import pandas, pyarrow, numpy, matplotlib, PIL" \
    && cline --version
ENTRYPOINT ["python", "/srv/bin/entrypoint.py"]
CMD ["cline"]
