FROM eclipse-temurin:17-jre-jammy

ARG APP_JAR=app.jar
ARG APP_PORT=8080
WORKDIR /srv/app
RUN mkdir -p /srv/app/config /srv/app/data /srv/app/logs
COPY ${APP_JAR} /srv/app/app.jar

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    TZ=Asia/Shanghai

EXPOSE ${APP_PORT}
ENTRYPOINT ["java", "-jar", "/srv/app/app.jar"]
