# Use a base image with Java 8 installed
FROM openjdk:8-jdk-alpine

# Copy your JAR files to the container
COPY ./im-platform/target/im-platform.jar im-platform.jar
COPY ./im-server/target/im-server.jar im-server.jar

RUN java -version
# Expose ports
EXPOSE 8888
EXPOSE 8877
EXPOSE 8878

# Define the startup command to run all JAR files
CMD ["java", "-jar", "im-platform.jar", "im-server.jar"]
