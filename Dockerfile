FROM core-registry.tools.cosmic.sky/core-engineering/release/alpine-java17:latest

# PoC image for Rootless containers using Podman and the Podman REST api based on Alpine. See README

USER root

RUN apk update

# Commented out as 'git' is not required for this demo image - add as needed.
#RUN apk --no-cache add git

# During debugging the PoC, 'curl' was used to run containers via the API. Keep or remove as needed.
RUN apk --no-cache add curl
RUN apk --no-cache add libstdc++ gcompat openssh unzip
RUN apk --no-cache add podman

# Disable RYUK from cleaing up the containers when the tests stop. RYUK is problematic in pipelines, so this is a
# recommended setting. As the pod is killed, clean up will happen anyway but it can mean dangling containers etc. during
# development. See the Testcontainer docs.
ENV TESTCONTAINERS_RYUK_DISABLED=true

# Testcontainers runs an Alpine instance to see if it is wired up correctly. Usually not an issue unless you are running
# in host network mode. See the README.
ENV TESTCONTAINERS_CHECKS_DISABLE=true

# Podman REST API exposed via TCP (as opposed to unix socket).
ENV DOCKER_HOST=tcp://localhost:9111

# To create an Alpine image using the Core image, everything between the dashed lines is (mostly) copied
# from the official Podman image: https://github.com/containers/podman/blob/main/contrib/podmanimage/upstream/Containerfile
# The 'mostly' reference above results from differences between Alpine and Redhat user creation.
# Original image: quay.io/podman/stable

# ------------------------- Start --------------------------------------------
RUN adduser --home /home/podman/ --disabled-password --uid 1000 podman; \
echo -e "podman:1:999\npodman:1001:64535" > /etc/subuid; \
echo -e "podman:1:999\npodman:1001:64535" > /etc/subgid;

ARG _REPO_URL="https://raw.githubusercontent.com/containers/podman/main/contrib/podmanimage/stable"
ADD $_REPO_URL/containers.conf /etc/containers/containers.conf
ADD $_REPO_URL/podman-containers.conf /home/podman/.config/containers/containers.conf

RUN mkdir -p /home/podman/.local/share/containers && \
    chown podman:podman -R /home/podman && \
    chmod 644 /etc/containers/containers.conf

# Copy & modify the defaults to provide reference if runtime changes needed.
# Changes here are required for running with fuse-overlay storage inside container.

# ******* Core has mandated the use of 'vfs' so feel free to refactor away the overlay bits
RUN sed -e 's|^#mount_program|mount_program|g' \
           -e '/additionalimage.*/a "/var/lib/shared",' \
           -e 's|^mountopt[[:space:]]*=.*$|mountopt = "nodev,fsync=0"|g' \
           /usr/share/containers/storage.conf \
           > /etc/containers/storage.conf

# Note VOLUME options must always happen after the chown call above
# RUN commands can not modify existing volumes
VOLUME /var/lib/containers
VOLUME /home/podman/.local/share/containers

RUN mkdir -p /var/lib/shared/overlay-images \
             /var/lib/shared/overlay-layers \
             /var/lib/shared/vfs-images \
             /var/lib/shared/vfs-layers && \
    touch /var/lib/shared/overlay-images/images.lock && \
    touch /var/lib/shared/overlay-layers/layers.lock && \
    touch /var/lib/shared/vfs-images/images.lock && \
    touch /var/lib/shared/vfs-layers/layers.lock

ENV _CONTAINERS_USERNS_CONFIGURED=""
# ------------------------- End --------------------------------------------

# Redundant ???
RUN chmod -R a=rwx /tmp

USER podman

ENV GRADLE_HOME=/home/podman/.gradle/

# When running in Rootless mode, the default network driver is 'host'. At this time, the host driver only works
# correctly on Linux systems, so the example app needs to trigger the correct Testcontainer mode during
# Bean configuration. Added the ENV var below to simplify things.
ENV SPRING_PROFILES_ACTIVE=linux

RUN curl --request GET -sL \
--url 'https://services.gradle.org/distributions/gradle-8.2.1-bin.zip' \
--output '/tmp/gradle.zip'

RUN unzip -o /tmp/gradle.zip -d /tmp
RUN rm /tmp/gradle.zip
RUN mv "/tmp/gradle-8.2.1/" "/tmp/gradle/"

COPY --chown=podman:root . /app/K8PodmanPinP/

WORKDIR /app/K8PodmanPinP/

RUN /tmp/gradle/bin/gradle wrapper
RUN ./gradlew clean
RUN rm -rf /tmp/gradle

EXPOSE 8080
