# Use a base image with Java 8 installed
FROM openjdk:8-jdk-alpine

# Copy your JAR files to the container
COPY ./target/im-platform.jar im-platform.jar

RUN java -version
# Expose ports
EXPOSE 8888


# Define the startup command to run all JAR files
CMD ["java", "-jar", "im-platform.jar"]
