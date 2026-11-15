ARG JAVA_IMAGE=eclipse-temurin:17-jdk-jammy
FROM ${JAVA_IMAGE} AS build
WORKDIR /build
COPY backend/lib lib
COPY backend/src src
RUN mkdir classes && javac -encoding UTF-8 -cp 'lib/*' -d classes src/Main.java
FROM ${JAVA_IMAGE}
WORKDIR /app
COPY --from=build /build/classes classes
COPY --from=build /build/lib lib
COPY backend/schema.sql .
COPY frontend web
USER 10001:10001
EXPOSE 8080
CMD ["java","-Xmx384m","-cp","classes:lib/*","Main"]
