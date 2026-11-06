# Image d'une instance de crèche : le serveur et le front (construite depuis la racine du dépôt).
#   docker build -f deploy/Dockerfile -t ticagou-web:0.1.0 .
FROM node:24-alpine AS web
WORKDIR /web
RUN corepack enable
COPY web/package.json web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY web/ ./
RUN pnpm build

FROM eclipse-temurin:21-jdk-alpine AS server
WORKDIR /build
COPY server/.mvn .mvn
COPY server/mvnw server/pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY server/src src
RUN ./mvnw -q -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine
# pg_dump et pg_restore : sauvegardes et test de restauration, depuis l'instance.
RUN apk add --no-cache postgresql17-client tzdata \
    && addgroup -S -g 1000 ticagou && adduser -S -u 1000 -G ticagou ticagou \
    && mkdir /data && chown ticagou:ticagou /data
WORKDIR /app
COPY --from=server /build/target/ticagou-server-*.jar app.jar
COPY --from=web /web/dist web
USER ticagou
EXPOSE 8080
ENV TZ=Pacific/Noumea \
    TICAGOU_WEB_ROOT=/app/web \
    TICAGOU_STORAGE_DIR=/data/documents \
    TICAGOU_BACKUP_DIR=/data/backups \
    TICAGOU_FORWARD_HEADERS=native \
    TICAGOU_MFA_REQUIRED=true \
    TICAGOU_API_DOCS=false
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=4 \
    CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
