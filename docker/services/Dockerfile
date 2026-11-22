# ScholarChain service image, shared by query-service, graph-service and embedding-worker
# (local build only: no push, no registry). `sbt dockerLocal` stages this file next to each
# deployable's assembled jar (app.jar) in target/docker/<image>/ and builds one image per
# folder, tagged scholarchain-<image>:local.
#
# Base: Amazon Corretto 17 (matches the toolchain JDK and the EMR 7.x Java), headless,
# on Amazon Linux 2023. Pinned by exact tag AND by the multi-arch index digest, so a
# moved tag cannot change the image. Bumping means changing both values together.
FROM docker.io/library/amazoncorretto:17.0.21-al2023-headless@sha256:dae4ff04ffa0bca0f1daf5761dc30dfaf52156025fdf8cb5ef6f340ae61cee0c

# Non-root: a numeric UID/GID, so no RUN step or distro tool is needed to create it.
# The jar is explicitly root-owned and mode 0644 (world-readable, enough for `java -jar`),
# so the runtime user cannot overwrite it; nothing in the image is writable by it except
# /tmp.
COPY --chown=0:0 --chmod=0644 app.jar /opt/scholarchain/app.jar
USER 10001:10001
WORKDIR /opt/scholarchain

# Memory is a percentage of the container limit, never a fixed -Xmx, so the same image
# fits any task size. No secrets and no endpoints here: configuration arrives at runtime
# from the task definition and AWS Secrets Manager.
ENTRYPOINT ["java", \
  "-XX:InitialRAMPercentage=25", \
  "-XX:MaxRAMPercentage=75", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "/opt/scholarchain/app.jar"]
