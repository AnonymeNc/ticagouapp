# Console de l'éditeur : une image avec le serveur et l'interface (construite depuis console/).
#   docker build -t ticagou-console .
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
# Identifiant fixe : creche.sh exporter donne les exports à l'utilisateur 1001, qui les lit sur /exports.
RUN addgroup -S -g 1001 console && adduser -S -u 1001 console -G console
WORKDIR /app
COPY --from=server /build/server/target/ticagou-console-*.jar app.jar
USER console
EXPOSE 8095
ENV TZ=Pacific/Noumea
HEALTHCHECK --interval=30s --timeout=5s CMD wget -qO- http://localhost:8095/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
