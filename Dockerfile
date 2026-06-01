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
ENV LOGIN_TO_EXECUTOR_abessarab=2397
ENV LOGIN_TO_EXECUTOR_aanisimov=2346
ENV LOGIN_TO_EXECUTOR_avershinin=2182
ENV LOGIN_TO_EXECUTOR_vlasova=547
ENV LOGIN_TO_EXECUTOR_avolodina=103
ENV LOGIN_TO_EXECUTOR_gmaslennikov=2768
ENV LOGIN_TO_EXECUTOR_npanainta=566
ENV LOGIN_TO_EXECUTOR_drodaev=2767
ENV LOGIN_TO_EXECUTOR_dsemenov=1952
ENV LOGIN_TO_EXECUTOR_schernyshova=104
ENV LOGIN_TO_EXECUTOR_ashkolnikov=582
ENV LOGIN_TO_EXECUTOR_rshkuro=2347
ENV LOGIN_TO_EXECUTOR_eyurpalova=1600

EXPOSE 8080

CMD ["java", "-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8","-jar", "app.jar"]