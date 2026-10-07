FROM eclipse-temurin:25-jre-noble AS runner
WORKDIR /app

# Run as non-root user for security best practice
RUN addgroup --system spring && adduser --system --ingroup spring spring
USER spring:spring

# Copy prebuilt executable JAR
COPY target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
