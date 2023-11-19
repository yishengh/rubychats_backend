# Use a base image with Java 8 installed
FROM openjdk:8-jdk-alpine

# Copy your JAR files to the container
COPY ./target/im-server.jar im-server.jar

RUN java -version
# Expose ports
EXPOSE 8877


# Define the startup command to run all JAR files
CMD ["java", "-jar", "im-server.jar"]
