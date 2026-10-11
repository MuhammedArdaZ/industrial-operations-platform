# Stage 1: Build JAR using Maven
FROM eclipse-temurin:25-jdk-noble AS builder
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw

# Download dependencies in a cached layer
RUN ./mvnw dependency:go-offline -B

# Copy source and build executable jar
COPY src/ src/
RUN ./mvnw clean package -DskipTests

# Stage 2: Minimal runtime image
FROM eclipse-temurin:25-jre-noble AS runner
WORKDIR /app

# Run as non-root user for security best practice
RUN addgroup --system spring && adduser --system --ingroup spring spring
USER spring:spring

# Copy prebuilt executable JAR from builder stage
COPY --from=builder /workspace/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
