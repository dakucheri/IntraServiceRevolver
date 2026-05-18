FROM amazoncorretto:25-alpine
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
WORKDIR /app
COPY target/Revolver-0.2-jar-with-dependencies.jar app.jar

# Переменные окружения
ENV APP_AUTH="Basic YWRtaW46N2ovSy04TGJCPw=="
ENV APP_CHECK_INTERVAL_MS=30000
ENV LOGIN_TO_EXECUTOR_ikornilov=319
ENV LOGIN_TO_EXECUTOR_employee-1=329

EXPOSE 8080

CMD ["java", "-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8","-jar", "app.jar"]