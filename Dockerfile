FROM azul/zulu-openjdk:22
COPY . /kafka
RUN cd /kafka && apt update && apt upgrade -y && apt install -y git wget curl && ./gradlew srcJar