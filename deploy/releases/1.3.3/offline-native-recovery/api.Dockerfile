ARG BASE_IMAGE
FROM ${BASE_IMAGE}
COPY --chown=10001:10001 app.jar /srv/app/app.jar
