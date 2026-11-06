# Portail parents : une image avec le service et la PWA (construite depuis parents-portal/).
#   docker build -t ticagou-portail .
FROM node:24-alpine AS web
WORKDIR /web
RUN corepack enable
COPY web/package.json web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY web/ ./
RUN pnpm build

FROM eclipse-temurin:21-jdk-alpine AS server
WORKDIR /build
COPY server/.mvn server/.mvn
COPY server/mvnw server/pom.xml server/
RUN cd server && chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY server/src server/src
COPY --from=web /web/dist web/dist
RUN cd server && ./mvnw -q -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S portail && adduser -S portail -G portail
WORKDIR /app
COPY --from=server /build/server/target/ticagou-parents-portal-*.jar app.jar
USER portail
EXPOSE 8090
ENV TZ=Pacific/Noumea
HEALTHCHECK --interval=30s --timeout=5s CMD wget -qO- http://localhost:8090/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
