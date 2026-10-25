FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S norman && adduser -S norman -G norman
WORKDIR /app
COPY --from=build --chown=norman:norman /build/target/norman-server-1.0.0.jar /app/server.jar
USER norman
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=55 -XX:MaxMetaspaceSize=160m -XX:MaxDirectMemorySize=64m -Xss512k"
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/server.jar"]
