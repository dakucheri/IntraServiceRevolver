FROM amazoncorretto:25-alpine
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
WORKDIR /app
COPY target/Revolver-0.2-jar-with-dependencies.jar app.jar

RUN mkdir -p /app/stats
VOLUME ["/app/stats"]

# Переменные окружения
ENV APP_AUTH="Basic aWtvcm5pbG92OiZKX24rdzkxXmo="
ENV APP_CHECK_INTERVAL_MS=60000
ENV APP_PARALLELISM=5
ENV LOGIN_TO_EXECUTOR_ikornilov=319
ENV LOGIN_TO_EXECUTOR_employee-1=566
ENV LOGIN_TO_EXECUTOR_employee-test-1=2184
ENV LOGIN_TO_EXECUTOR_employee-test-2=100
ENV LOGIN_TO_EXECUTOR_employee-test-3=2768
ENV LOGIN_TO_EXECUTOR_employee-test-4=581
ENV LOGIN_TO_EXECUTOR_employee-test-5=2438

EXPOSE 8080

CMD ["java", "-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8","-jar", "app.jar"]