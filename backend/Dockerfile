# Stage 1: Build stage
FROM eclipse-temurin:25-jdk-noble AS builder
WORKDIR /workspace

# Copy maven wrapper and pom.xml first for layer caching
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Ensure mvnw has executable permissions
RUN chmod +x mvnw

# Download dependencies (cached layer)
RUN ./mvnw dependency:go-offline -B

# Copy application source code and package executable JAR
COPY src src
RUN ./mvnw clean package -DskipTests

# Stage 2: Runtime stage
FROM eclipse-temurin:25-jre-noble AS runner
WORKDIR /app

# Run as non-root user for security best practice
RUN addgroup --system spring && adduser --system --ingroup spring spring
USER spring:spring

# Copy built JAR from builder stage
COPY --from=builder /workspace/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
