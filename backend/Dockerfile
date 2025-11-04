# Etapa base con Maven
FROM maven:3.9.4-eclipse-temurin-21 AS base
WORKDIR /app

# Copiar Maven Wrapper y pom.xml
COPY pom.xml mvnw ./
COPY .mvn .mvn

# Asegurar permisos de ejecución para mvnw
RUN chmod +x mvnw

# Descargar dependencias sin compilar
RUN ./mvnw dependency:go-offline -B

# Copiar código fuente
COPY src ./src

# Etapa producción: build del JAR
FROM base AS prod
RUN ./mvnw clean package -DskipTests

# Imagen ligera para producción
FROM eclipse-temurin:21-jdk-alpine AS final
WORKDIR /app

# Copiar JAR compilado
COPY --from=prod /app/target/*.jar app.jar

# Copiar clave privada
COPY priv_arendel.pem /app/priv_arendel.pem

# Crear usuario no-root
RUN addgroup -g 1000 spring && \
    adduser -D -u 1000 -G spring spring && \
    chown -R spring:spring /app

USER spring

EXPOSE 8080

# Healthcheck
HEALTHCHECK --interval=10m --timeout=3s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/v1/token || exit 1

CMD ["java", "-jar", "app.jar"]