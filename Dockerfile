FROM openjdk:14-alpine
COPY ./target/scala-2.13/system-rep.jar /app/src/app.jar
WORKDIR /app/src
#ENTRYPOINT ["java", "-jar","app.jar"]
ENTRYPOINT ["java", "-cp","app.jar","mx.cinvestav.Main"]
