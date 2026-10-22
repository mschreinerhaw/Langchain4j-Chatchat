# Upgrade an immutable, already verified Python environment without reinstalling
# or changing its Python dependencies. The base must already contain Noto CJK;
# verify-fonts.py fails the build if those bundled fonts are missing. Resolve and
# check the local base tag's immutable ID before building; never use latest.
ARG PYTHON_RUNTIME_IMAGE
FROM ${PYTHON_RUNTIME_IMAGE}
USER root
ENV MATPLOTLIBRC=/opt/chatchat/matplotlib
COPY matplotlibrc /opt/chatchat/matplotlib/matplotlibrc
COPY verify-fonts.py /opt/chatchat/verify-fonts.py
COPY configure-fonts.py /opt/chatchat/configure-fonts.py
RUN python /opt/chatchat/configure-fonts.py
USER 10001:10001
RUN python /opt/chatchat/verify-fonts.py
