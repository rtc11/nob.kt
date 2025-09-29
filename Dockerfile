FROM eclipse-temurin:21-jdk-alpine AS jre

# jdeps --print-module-deps --ignore-missing-deps out/example-fat.jar
# jdeps --print-module-deps --ignore-missing-deps --class-path "out/example/runtime-libs/*" --multi-release base out/example.jar
RUN jlink \
    --verbose \
    --module-path $JAVA_HOME/jmods \
    # --add-modules java.base \ # always included
    --add-modules java.desktop \
    --add-modules java.instrument \
    --add-modules java.logging \
    --add-modules java.management \
    --add-modules jdk.unsupported \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=zip-6 \
    --output /customjre

FROM alpine:latest AS app
ENV LANG="nb_NO.UTF-8" LANGUAGE="nb_NO:nb" LC_ALL="nb:NO.UTF-8" TZ="Europe/Oslo"
ENV JAVA_HOME=/jre
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=jre /customjre $JAVA_HOME
RUN mkdir /runtime-libs
COPY out/example/runtime-libs/ /runtime-libs/
COPY out/example.jar /example.jar

# docker build -t nob:example .
# docker run -p 8080:8080 nob:example
ENTRYPOINT [ "java", "-cp", "/runtime-libs/*:/example.jar", "MainKt" ]
